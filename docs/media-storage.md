# Almacenamiento de medios: arquitectura y resolución de deuda técnica

Este documento describe la arquitectura de almacenamiento de objetos, el ciclo de vida seguro de medios y la resolución de la deuda técnica previa en FR-UC-17 (portafolio del Fixer).

---

## 1. Estado de los casos de uso

- **FR-UC-17 (Portafolio del Fixer): Deuda técnica resuelta.**
  - Eliminado el uso de `storageKey` libre y `kind` desde el cliente.
  - Flujo de carga controlado por tickets firmados y asociación por identificador único (`mediaId`).
  - Validación física de magic bytes contra ataques de tipo MIME spoofing.
  - Bucket privado con CORS configurado para clientes autorizados (`http://localhost:4200`, `http://localhost`, `capacitor://localhost`).
  - Bloqueo pesimista sobre `fixer_portfolios` y regla estricta de mínimo 3 fotografías visibles para publicación.
  - Eliminación segura post-commit (`@TransactionalEventListener(phase = AFTER_COMMIT)`) respaldada por tabla de trabajos pendientes (`media_deletion_jobs`).

- **FR-UC-16 (Documentos de verificación del Fixer):**
  - Mantiene temporalmente su contrato con `storageKey` hasta su respectiva iteración de migración al ciclo controlado de medios.

---

## 2. Modelo de almacenamiento privado y URLs firmadas

El almacenamiento de objetos opera bajo el principio de menor privilegio: el bucket de almacenamiento es **estrictamente privado** y rechaza cualquier acceso anónimo directo (HTTP 403).

### 2.1 Proveedores y configuración
- **Desarrollo y pruebas locales:** Servicio S3-compatible provisto por MinIO (`quay.io/minio/minio:latest`) configurado en `compose.development.yml`.
- **Producción:** Amazon Web Services S3.
- **Credenciales:**
  - En producción, las variables de entorno `FIXUP_STORAGE_ACCESS_KEY` y `FIXUP_STORAGE_SECRET_KEY` son obligatorias (no existen credenciales por defecto en `application.yml`).
  - En desarrollo, las credenciales se configuran mediante `.env.example`, `compose.development.yml` y `application-dev.yml`.
  - La auto-creación de buckets (`auto-create-bucket: true`) está estrictamente limitada a los perfiles `development` y `test`. En producción está deshabilitada (`auto-create-bucket: false`).

### 2.2 Tiempos de expiración de URLs firmadas
- **URL firmada de subida (PUT):** 15 minutos de vigencia.
- **URL firmada de lectura (GET):** 5 minutos de vigencia.
- **Presigning consistente:** El `S3Presigner` firma utilizando directamente el endpoint público visible por el cliente/navegador (`http://localhost:9000` en desarrollo), evitando inconsistencias de host que invaliden las firmas S3. El frontend nunca debe adjuntar cabeceras de autorización JWT al invocar URLs firmadas S3/MinIO.

---

## 3. Ciclo de vida de MediaAsset

Todo medio gestionado por la plataforma transita por los siguientes estados:

```text
[ PENDING ] ──(Expiración / timeout)──────────────────────────► [ EXPIRED ]
     │
     ├────(Firma de archivo no coincide)──────────────────────► [ INVALID ] ──► (Borrado físico S3)
     │
     ▼ (Validación de tamaño + magic bytes correcta)
 [ READY ] ──(Asociación a pieza de portafolio)────────────────► [ ATTACHED ]
                                                                     │
                                                                     ▼ (Eliminación de pieza)
 [ DELETED ] ◄──(Commit BD + Borrado físico S3 completado)── [ DELETION_PENDING ]
```

### 3.1 Idempotencia en confirmación (`POST /media/uploads/{mediaId}/confirm`)
- `PENDING` + archivo válido en almacenamiento $\rightarrow$ `READY` (HTTP 200).
- `READY` $\rightarrow$ `READY` (HTTP 200).
- `ATTACHED` $\rightarrow$ `ATTACHED` (HTTP 200).
- `EXPIRED` $\rightarrow$ HTTP 409 `UPLOAD_EXPIRED`.
- `INVALID` $\rightarrow$ HTTP 409 `MEDIA_INVALID`.
- `DELETED` $\rightarrow$ HTTP 404 `MEDIA_NOT_FOUND`.

### 3.2 Validación de magic bytes reales
Durante `confirmUpload`, el backend inspecciona los primeros bytes del objeto en almacenamiento para verificar su firma real:
- **JPEG:** `FF D8 FF`
- **PNG:** `89 50 4E 47 0D 0A 1A 0A`
- **WebP:** `RIFF....WEBP`

Si un cliente declara `image/jpeg` pero sube un ejecutable, script o formato no soportado, la operación falla inmediatamente con HTTP 415 `MEDIA_TYPE_NOT_ALLOWED`, el estado del medio se persiste como `INVALID` y el objeto es purgado de S3.

---

## 4. Reglas de negocio y concurrencia en portafolio (FR-UC-17)

1. **Bloqueo a nivel de fila (`fixer_portfolios`):**
   - La tabla `fixer_portfolios` contiene una clave foránea `fk_portfolio_user` referenciando `users(id)`.
   - Todas las operaciones que mutan el portafolio (publicar pieza, cambiar visibilidad, eliminar, publicar o despublicar portafolio) adquieren un bloqueo pesimista (`SELECT ... FOR UPDATE`) sobre la fila del portafolio del Fixer.
2. **Regla de 3 fotografías visibles:**
   - Para publicar el portafolio (`POST /media/me/portfolio/publish`), el Fixer debe tener al menos 3 piezas activas, no eliminadas, con visibilidad `PUBLIC` y cuyo medio esté en estado `ATTACHED`.
   - Si un portafolio está `PUBLISHED` y una eliminación o acción de ocultar reduce las fotografías visibles a menos de 3, el estado del portafolio **revierte automáticamente a `DRAFT`**.
   - Volver a mostrar una tercera fotografía no republica automáticamente: el Fixer debe invocar explícitamente `/publish`.
   - La consulta pública `GET /media/fixers/{fixerUserId}/portfolio` solo expone piezas si el portafolio está en estado `PUBLISHED`; de lo contrario, responde HTTP 404 `PORTFOLIO_NOT_FOUND`.
3. **Eliminación atómica y consistente:**
   - La eliminación de piezas se realiza dentro de la transacción de base de datos marcando el medio como `DELETION_PENDING` y encolando un registro en `media_deletion_jobs`.
   - El borrado físico del objeto en S3 se ejecuta de manera confiable tras la confirmación de la transacción (`@TransactionalEventListener(phase = AFTER_COMMIT)`). Al completarse, el medio transita a `DELETED`.
