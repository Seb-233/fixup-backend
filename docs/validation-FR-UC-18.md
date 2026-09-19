# Validación FR-UC-18 — responder solicitudes y cotizar

Fecha local: 2026-09-18 (America/Bogota). Repositorio: Seb-233/fixup-backend.
Rama: `feature/fr-uc-18-quotations`.
Base: `develop`, commit `78fb6a5`. Verificación ejecutada sobre OpenJDK 21.0.8 en Windows y contenedor Linux / PostgreSQL 17.

## Estado del alcance (Criterios FR-UC-18)

### Implementado en esta fase
1. **Creación de cotizaciones**:
   - `POST /quotations` con monto (pesos colombianos enteros), estimación de días y mensaje opcional.
2. **Autorización por roles**:
   - `GET /requests/open` y `POST /quotations` exigen cuenta activa (`ACTIVE`), rol `FIXER` y verificación aprobada (`VERIFIED`).
   - `GET /requests/me` y `POST /requests` restringidos al propietario (`OWNER`).
   - `POST /quotations/{id}/accept` y `POST /quotations/{id}/reject` restringidos al propietario de la solicitud.
3. **Compatibilidad persistida por especialidad**:
   - Tabla `fixer_specialties` persistida en PostgreSQL (`fixer_user_id` FK a `fixer_profiles.user_id`, `specialty VARCHAR(50)`).
   - Eliminado almacenamiento en memoria estática (`OVERRIDDEN_SPECIALTIES` / `ConcurrentHashMap`).
   - Eliminado `assignSpecialties()` de la API pública productiva; especialidades consultadas a través del puerto `FixerEligibility.specialtiesOf()`.
   - `SubmitQuotation` valida compatibilidad directa: si la especialidad de la solicitud no está entre las del Fixer, devuelve 403 `ACCESS_DENIED`.
   - `GET /requests/open` filtra exclusivamente solicitudes compatibles con las especialidades del Fixer.
4. **Prevención de duplicados**:
   - Restricción única de base de datos `uq_quotation_request_fixer` en `(request_id, fixer_user_id)`.
   - Consulta previa `existsByRequestAndFixer()` y traducción acotada de violación de unicidad en adaptador de persistencia a HTTP 409 con código `ALREADY_QUOTED`.
   - Otros errores de integridad permanecen como 500 sin clasificarse erróneamente como cotización duplicada.
5. **Aceptación y rechazo**:
   - `POST /quotations/{quotationId}/accept` acepta la oferta elegida, rechaza automáticamente las ofertas competidoras y asigna la solicitud en una única transacción atómica.
   - `POST /quotations/{quotationId}/reject` permite al propietario descartar explícitamente una cotización sin aceptar otra.
6. **Protección de datos y privacidad**:
   - `OpenRequestSummaryResponse` no incluye `ownerUserId`, `description`, `photoKeys` ni `assignedFixerUserId`.
   - Respuestas de error 403 `ACCESS_DENIED` no revelan datos de recursos inexistentes o protegidos.
   - Se eliminaron afirmaciones de URLs firmadas en controladores y OpenAPI a la espera de la integración de media.
7. **Control de concurrencia y orden de bloqueo**:
   - Bloqueo pesimista ordenado (`lockForDecision` sobre la solicitud padre primero, seguido de bloqueo sobre ofertas en orden determinístico) para prevenir deadlocks.
   - Publicación transaccional de evento `QuotationAccepted` para consumidores asíncronos con `@TransactionalEventListener(phase = AFTER_COMMIT)`.

### Pendiente para fases sucesivas (Tras integrar PR #26 y PR #27)
1. **Creación del trabajo después de aceptar (`jobs`)**:
   - Desacoplado mediante el evento `QuotationAccepted`.
2. **Notificaciones automáticas**:
   - Notificación a fixers al ser aceptada o rechazada su oferta.
3. **Historial completo de estados**:
   - Auditoría de transiciones y motivos de rechazo.
4. **Integración definitiva con media (PR #26)**:
   - Reemplazo de claves provisionales `photoKeys` por identificadores definitivos de evidencias verificadas.
5. **Renumeración de migraciones Flyway**:
   - El script `V3__repair_requests_and_quotations.sql` se renumerará a su ordinal definitivo una vez se fusionen los PR #26 y #27 en `develop`.
6. **Regeneración final de OpenAPI**:
   - Actualización final del contrato global tras resolver los PR pendientes.

## Contrato expuesto

| Método | Ruta | Código éxito | Descripción |
| --- | --- | --- | --- |
| POST | `/requests` | 201 | Abrir solicitud de reparación |
| GET | `/requests/me` | 200 | Listar solicitudes del usuario autenticado |
| GET | `/requests/open` | 200 | Bandeja resumen de solicitudes abiertas según especialidad del Fixer |
| GET | `/requests/{requestId}` | 200 | Detalle autorizado de la solicitud |
| POST | `/quotations` | 201 | Enviar cotización (Fixer verificado compatible) |
| GET | `/quotations/me` | 200 | Listar cotizaciones enviadas por el Fixer |
| GET | `/quotations/for-request/{requestId}` | 200 | Comparar ofertas recibidas (ordenadas por menor precio) |
| POST | `/quotations/{quotationId}/accept` | 200 | Aceptar oferta, rechazar competidoras y asignar solicitud |
| POST | `/quotations/{quotationId}/reject` | 200 | Rechazo explícito de una oferta por el propietario |

## Verificación automatizada

| Suite | Comando | Resultado comprobado |
| --- | --- | --- |
| Suite completa H2 | `./mvnw --batch-mode --no-transfer-progress clean verify` | **BUILD SUCCESS**: 130 pruebas ejecutadas, 0 fallos, 0 errores, 0 omitidas |
| Suite completa PostgreSQL 17 | `./mvnw --batch-mode --no-transfer-progress -Ppostgres-it clean verify` | **BUILD SUCCESS**: 96 pruebas ejecutadas contra PostgreSQL 17 real (Testcontainers), 0 fallos, 0 errores |
| Arquitectura y dependencias modulares | ArchUnit & Spring Modulith | Aprobado sin ciclos ni dependencias circulares entre módulos |
| Contrato OpenAPI | `OpenApiContractTest` | Aprobado (`docs/openapi.json` regenerado desde la aplicación) |

### Casos de prueba en `QuotationHttpContract` (H2 y PostgreSQL 17)
1. `unverifiedFixerCannotReadInboxOrDetail`: Fixer no verificado recibe 403 en bandeja y en detalle.
2. `incompatibleFixerDoesNotReceiveNorReadRequest`: Fixer con especialidad incompatible no recibe la solicitud en bandeja ni puede consultar su detalle (403).
3. `compatibleVerifiedFixerCanListAndReadDetail`: Fixer verificado y compatible recibe la solicitud y lee su detalle (200).
4. `strangerCannotReadDetail`: Usuario ajeno sin relación recibe 403 sin filtración de información.
5. `ownerCanQueryOwnResources`: Propietario consulta sus solicitudes y sus cotizaciones.
6. `privateFieldsAbsentInSummaryResponse`: Comprueba que `OpenRequestSummaryResponse` no expone `ownerUserId`, `description`, `photoKeys` ni `assignedFixerUserId`.
7. `quotationOfAnotherOwnerReturns403`: Intento de aceptar o rechazar cotizaciones pertenecientes a solicitudes de otro usuario devuelve 403.
8. `explicitRejectionByOwnerTransitionsToRejected`: Propietario rechaza cotización individualmente (200), y un segundo rechazo devuelve 409 `QUOTATION_NOT_SUBMITTED`.
9. `coherentAcceptanceEventPublished`: Publicación de `QuotationAccepted` con identificadores coherentes de solicitud, cotización, propietario, Fixer y monto.
10. `twoSimultaneousAcceptancesOnlyOneWins`: Dos aceptaciones simultáneas sobre ofertas de la misma solicitud; exactamente una gana (200), la otra recibe conflicto (409), una cotización queda ACCEPTED, una REJECTED y la solicitud ASSIGNED.
11. `concurrentDuplicateQuotationGets409Never500`: Dos solicitudes simultáneas de cotización del mismo técnico sobre la misma solicitud; una genera 201 y la otra 409 con código `ALREADY_QUOTED`, nunca 500.
12. `verifiedCompatibleFixerCanSubmitQuotation`: Fixer verificado y compatible crea cotización exitosamente (201).
13. `verifiedIncompatibleFixerCannotSubmitQuotation`: Fixer verificado pero incompatible recibe 403 `ACCESS_DENIED` sin revelar datos del recurso.
14. `unverifiedFixerCannotSubmitQuotation`: Fixer no verificado recibe 403 `ACCESS_DENIED`.
15. `userWithoutFixerRoleCannotSubmitQuotation`: Usuario sin rol FIXER recibe 403 `ACCESS_DENIED`.
16. `submittingQuotationForNonExistentRequestReturnsNotFound`: Solicitud inexistente devuelve 404 `REQUEST_NOT_FOUND`.
17. `submittingQuotationForNonOpenRequestReturnsConflict`: Solicitud no OPEN devuelve 409 `REQUEST_NOT_OPEN`.
18. `secondQuotationFromSameFixerReturnsConflict`: Segundo intento del mismo Fixer devuelve 409 `ALREADY_QUOTED`.

## Compromisos y entrega

- Commits separados, limpios y firmados con SSH.
- No se realizó merge a `develop`.
- No se modificó código frontend.
- No se renumeró la migración Flyway `V3` (se ajustará tras integrar PR #26 y PR #27).
- No se versionaron credenciales, secretos ni archivos temporales.
- El issue permanece abierto hasta completar los criterios diferidos (jobs, notificaciones, media definitiva).
