# Almacenamiento de medios: arquitectura y resolución de deuda técnica

Este documento describe la arquitectura de almacenamiento de objetos, el ciclo de vida seguro de medios y la resolución definitiva de los bloqueos de diseño, persistencia, concurrencia y resiliencia en FR-UC-17 (portafolio visual del Fixer).

---

## 1. Estado de los casos de uso

- **FR-UC-17 (Portafolio del Fixer): Deuda técnica y bloqueos resueltos.**
  - Eliminado el uso de `storageKey` libre y `kind` desde el cliente.
  - Flujo de carga controlado por tickets firmados y asociación por identificador único (`mediaId`).
  - Validación física de magic bytes y correspondencia de metadatos `Content-Type` contra ataques de MIME spoofing.
  - Bucket privado con CORS configurado para clientes autorizados (`http://localhost:4200`, `http://localhost`, `capacitor://localhost`).
  - Concurrencia atómica del primer portafolio mediante `INSERT ... ON CONFLICT (fixer_user_id) DO NOTHING` seguido de `SELECT ... FOR UPDATE`, sin control de flujo por excepciones.
  - Procesamiento durable, desacoplado y con reintentos seguros para la eliminación de objetos en almacenamiento.
  - Rutas REST estabilizadas bajo el recurso `/pieces` y respuestas 404 `PIECE_NOT_FOUND` indistinguibles para piezas inexistentes y ajenas.

- **FR-UC-16 (Documentos de verificación del Fixer):**
  - Mantiene temporalmente su contrato con `storageKey` hasta su respectiva iteración de migración al ciclo controlado de medios.

---

## 2. Rutas Definitivas del Contrato HTTP

La API expone exclusivamente las siguientes rutas para medios y portafolio:

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/media/uploads` | Solicita un ticket firmado para subida directa (PUT) |
| `POST` | `/media/uploads/{mediaId}/confirm` | Confirma la subida tras validar existencia, tamaño, MIME y magic bytes |
| `GET` | `/media/me/portfolio` | Lista el portafolio propio del técnico (incluye piezas ocultas) |
| `POST` | `/media/me/portfolio/pieces` | Publica/adjunta una nueva pieza al portafolio |
| `POST` | `/media/me/portfolio/publish` | Publica el portafolio (exige $\ge 3$ fotos visibles) |
| `POST` | `/media/me/portfolio/unpublish` | Despublica el portafolio (revierte a `DRAFT`) |
| `DELETE` | `/media/me/portfolio/pieces/{pieceId}` | Elimina una pieza y programa el borrado físico durable |
| `POST` | `/media/me/portfolio/pieces/{pieceId}/hide` | Oculta una pieza del portafolio público |
| `POST` | `/media/me/portfolio/pieces/{pieceId}/show` | Vuelve pública una pieza previamente oculta |
| `GET` | `/media/fixers/{fixerUserId}/portfolio` | Consulta pública del portafolio publicado de un técnico |

> [!NOTE]
> Las rutas anteriores sin `/pieces` han sido eliminadas por completo sin alias ni redirecciones.

---

## 3. Modelo de Almacenamiento Privado y URLs Firmadas

El bucket de almacenamiento es **estrictamente privado** y rechaza cualquier acceso anónimo directo (HTTP 403).

### 3.1 Proveedores y configuración
- **Desarrollo y pruebas locales:** Servicio S3-compatible provisto por MinIO (`quay.io/minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e`) configurado en `compose.development.yml`.
- **Producción:** Amazon Web Services S3.
- **Credenciales:**
  - En producción, las variables de entorno `FIXUP_STORAGE_ACCESS_KEY` y `FIXUP_STORAGE_SECRET_KEY` son obligatorias (no existen valores predeterminados en `application.yml`).
  - En desarrollo, las credenciales se configuran mediante `.env.example`, `compose.development.yml` y `application-dev.yml`.
  - La auto-creación de buckets (`auto-create-bucket: true`) está estrictamente limitada a los perfiles `development` y `test`. En producción está deshabilitada (`auto-create-bucket: false`).

### 3.2 Tiempos de expiración de URLs firmadas
- **URL firmada de subida (PUT):** 15 minutos de vigencia.
- **URL firmada de lectura (GET):** 5 minutos de vigencia.
- **Presigning consistente:** El `S3Presigner` firma utilizando directamente el endpoint público visible por el cliente/navegador (`http://localhost:9000` en desarrollo). El frontend nunca debe adjuntar cabeceras de autorización JWT al invocar URLs firmadas S3/MinIO.

---

## 4. Ciclo de Vida de MediaAsset y Purga Durable

Todo medio gestionado por la plataforma transita por los siguientes estados:

```text
[ PENDING ] ──(Expiración / timeout)──────────────────────────► [ EXPIRED ]
     │
     ├────(Firma de archivo o MIME no coincide)───────────────► [ INVALID ] ──► (Purga durable S3)
     │                                                                           (Permanece INVALID)
     ▼ (Validación de tamaño + MIME + magic bytes correcta)
 [ READY ] ──(Asociación a pieza de portafolio)────────────────► [ ATTACHED ]
                                                                     │
                                                                     ▼ (Eliminación de pieza)
 [ DELETED ] ◄──(Borrado físico S3 completado)──────────────── [ DELETION_PENDING ]
```

### 4.1 Idempotencia en confirmación (`POST /media/uploads/{mediaId}/confirm`)
- `PENDING` + archivo válido en almacenamiento $\rightarrow$ `READY` (HTTP 200).
- `READY` $\rightarrow$ `READY` (HTTP 200).
- `ATTACHED` $\rightarrow$ `ATTACHED` (HTTP 200).
- `EXPIRED` $\rightarrow$ HTTP 409 `UPLOAD_EXPIRED`.
- `INVALID` $\rightarrow$ HTTP 409 `MEDIA_INVALID`.
- `DELETED` $\rightarrow$ HTTP 404 `MEDIA_NOT_FOUND`.

### 4.2 Validación rigurosa de metadatos y magic bytes
Durante `confirmUpload`:
1. Compara el `Content-Type` almacenado en S3 con el declarado en `MediaAsset`: si difieren o no pertenecen a los permitidos (`image/jpeg`, `image/png`, `image/webp`), responde HTTP 409 `MEDIA_NOT_READY`.
2. Valida la firma física de magic bytes:
   - **JPEG:** `FF D8 FF`
   - **PNG:** `89 50 4E 47 0D 0A 1A 0A`
   - **WebP:** `RIFF....WEBP`
3. Si los bytes no corresponden al tipo declarado, el medio pasa a `INVALID`, se programa un trabajo durable de purga (`INVALID_PURGE`), se confirma la transacción y se responde HTTP 415 `MEDIA_TYPE_NOT_ALLOWED`.
4. La posterior eliminación física del objeto en almacenamiento **no altera** el estado `INVALID` de `MediaAsset`, asegurando que confirmaciones posteriores respondan HTTP 409 `MEDIA_INVALID` y nunca degraden a 404.

---

## 5. Arquitectura de Eliminación Durable y Concurrente

Para evitar transacciones prolongadas con conexiones a servicios externos y garantizar resiliencia tras caídas del sistema, el subsistema de eliminación se divide en componentes con responsabilidades estrictas:

```text
Transacción Principal
┌────────────────────────┐
│  DeletePortfolioPiece  │
│  o ConfirmUpload       │
└───────────┬────────────┘
            │ 1. schedulePieceDeletion / scheduleInvalidObjectPurge
            ▼
┌────────────────────────┐
│  MediaDeletionService  │ ──► Guarda job PENDING en media_deletion_jobs
└───────────┬────────────┘ ──► Publica MediaDeletionRequested
            │
      AFTER_COMMIT
            │
            ▼
┌────────────────────────┐
│  MediaDeletionWorker   │ ◄── Polling periódico (@Scheduled) o evento reactivo
└───────────┬────────────┘
            │ 2. claimSpecificJob / claimNextBatch (REQUIRES_NEW + claimToken)
            ▼
┌────────────────────────┐
│ MediaDeletionJobClaimer│ ──► Reclama filas con FOR UPDATE SKIP LOCKED
└───────────┬────────────┘     Transición atómica a PROCESSING con claim_token
            │
            ▼
┌─────────────────────────┐
│MediaDeletionJobExecutor │ ──► Llama a ObjectStorage.delete() SIN transacción abierta
└───────────┬─────────────┘
            │ 3. completeJob / failJob (REQUIRES_NEW)
            ▼
┌──────────────────────────┐
│MediaDeletionJobFinalizer │ ──► Valida (id, PROCESSING, claim_token)
└──────────────────────────┘     - Éxito: COMPLETED + DELETED (si PIECE_DELETION)
                                 - Fallo: FAILED + backoff determinista (Clock)
```

### 5.1 Reclamación segura (`claim_token`)
- Cada reclamación genera un `claim_token` (UUID) nuevo y registra `locked_at`.
- La finalización (`completeJob` o `failJob`) solo tiene efecto si coinciden `id`, `status = 'PROCESSING'` y `claim_token`. Esto previene condiciones de carrera si un worker reanuda un trabajo que ya fue recuperado por otro tras un timeout.

### 5.2 Recuperación de trabajos estancados
- Los trabajos que permanezcan en `PROCESSING` por más de `lock-timeout` (5 minutos por defecto) son revertidos automáticamente a `PENDING`, limpiando `locked_at` y `claim_token` antes de reclamar el siguiente lote. Esto garantiza recuperación automática si el backend se reinicia inesperadamente.

### 5.3 Límite de intentos y errores sanitizados
- La selección de trabajos exige `attempts < max_attempts`.
- Al alcanzar el número máximo de intentos (`max_attempts: 5`), el trabajo permanece en `FAILED` sin `next_attempt_at` y sin `claim_token`.
- Los errores registrados en `last_error` son códigos sanitizados estables (`STORAGE_DELETE_FAILED`, `STORAGE_TIMEOUT`, `STORAGE_UNAVAILABLE`), impidiendo la filtración de URLs firmadas, credenciales o endpoints en base de datos o logs.

---

## 6. Reglas de Negocio y Concurrencia en Portafolio (FR-UC-17)

1. **Creación atómica del primer portafolio:**
   - La inserción inicial del portafolio se ejecuta mediante la consulta nativa:
     ```sql
     INSERT INTO fixer_portfolios (fixer_user_id, status, published_at, updated_at)
     VALUES (:fixerUserId, 'DRAFT', NULL, :updatedAt)
     ON CONFLICT (fixer_user_id) DO NOTHING;
     ```
   - Seguidamente se obtiene el bloqueo pesimista mediante `SELECT ... FOR UPDATE`. No se utilizan excepciones de unicidad como control de flujo, evitando invalidar transacciones en PostgreSQL.
2. **Regla de 3 fotografías visibles:**
   - Para publicar el portafolio (`POST /media/me/portfolio/publish`), el Fixer debe tener al menos 3 piezas activas, con visibilidad `PUBLIC` y medio `ATTACHED`.
   - Si una eliminación o acción de ocultar reduce las fotografías visibles a menos de 3, el estado del portafolio revierte automáticamente a `DRAFT`.
3. **Seguridad y 404 unificado en piezas:**
   - Consultar, ocultar o eliminar una pieza inexistente o perteneciente a otro técnico produce exactamente la misma respuesta: HTTP 404 `PIECE_NOT_FOUND`, sin revelar detalles de existencia ni de propiedad del recurso.
