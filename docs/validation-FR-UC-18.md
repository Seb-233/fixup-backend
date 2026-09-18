# Validación FR-UC-18 — responder solicitudes y cotizar

Fecha local: 2026-09-18 (America/Bogota). Repositorio: Seb-233/fixup-backend.
Rama: `feature/fr-uc-18-quotations`.
Base: `develop`, commit `78fb6a5`. Verificación ejecutada el 2026-09-18 sobre
OpenJDK 21.0.8 / 21.0.12 en Windows y contenedor Linux.

## Alcance y correcciones del PR #28

Esta revisión corrige y robustece el corte vertical de FR-UC-18 sobre los módulos
`requests` y `quotations`, resolviendo autorización, concurrencia, privacidad y pruebas
sin depender del contrato definitivo de almacenamiento (PR #26):

1. **Autorización estricta de bandeja abierta (`GET /requests/open`)**:
   - Exige cuenta `ACTIVE`, rol `FIXER` y estado de verificación `VERIFIED`.
   - Se eliminó el parámetro de consulta arbitrario donde el cliente seleccionaba cualquier
     especialidad.
   - Las especialidades se obtienen internamente desde el perfil del Fixer mediante el contrato
     público `FixerEligibility.specialtiesOf(CurrentActor)`.
   - Se devuelven exclusivamente solicitudes compatibles con al menos una especialidad del Fixer.
   - Toda la autorización se resuelve en el backend; no se delega en filtros de interfaz.

2. **Separación de DTO y privacidad**:
   - `OpenRequestSummaryResponse` para la bandeja: `requestId`, `specialty`, `title`, `createdAt`.
     No expone `ownerUserId`, `description`, `photoKeys` ni `assignedFixerUserId`.
   - `RequestDetailResponse` para el detalle autorizado: `requestId`, `specialty`, `title`,
     `description`, `photoKeys` (provisionales), `status`, `assignedFixerUserId` y `createdAt`.

3. **Acceso controlado al detalle (`GET /requests/{requestId}`)**:
   - Responde HTTP 200 exclusivamente para:
     * Propietario de la solicitud.
     * Fixer verificado y compatible mientras la solicitud esté `OPEN`.
     * Fixer asignado una vez aceptada la solicitud (`ASSIGNED`).
     * Administrador de la plataforma (`PLATFORM_ADMIN`).
   - Cualquier otro usuario recibe HTTP 403 con código `ACCESS_DENIED` sin filtración de datos.

4. **Prevención de deadlocks en aceptación de cotización (`AcceptQuotation`)**:
   - Orden estricto de bloqueos pesimistas (`PESSIMISTIC_WRITE`):
     1. Bloquear primero la solicitud en `repair_requests` por `requestId` (`lockForDecision`).
     2. Verificar que continúe en estado `OPEN`.
     3. Verificar que pertenezca al usuario autenticado.
     4. Bloquear todas las cotizaciones de dicha solicitud en `quotations`.
     5. Verificar que la cotización elegida continúe en estado `SUBMITTED`.
     6. Aceptar la seleccionada (`ACCEPTED`).
     7. Rechazar las restantes (`REJECTED`).
     8. Asignar la solicitud al Fixer correspondiente (`ASSIGNED`).
     9. Publicar el evento de dominio `QuotationAccepted`.
   - Se ejecuta en una única transacción atómica. Bloquear primero la solicitud padre previene
     deadlocks entre aceptaciones concurrentes sobre ofertas distintas de la misma solicitud.

5. **Manejo de cotización duplicada**:
   - Se mantiene la restricción única `(request_id, fixer_user_id)` en PostgreSQL.
   - Se captura `DataIntegrityViolationException` ante inserciones concurrentes y se traduce
     a HTTP 409 con código `ALREADY_QUOTED`.
   - Se probó exhaustivamente bajo concurrencia: nunca produce HTTP 500.

6. **Evento de aceptación (`QuotationAccepted`)**:
   - Se publica desde el caso de uso una vez aplicados los cambios.
   - Se documenta en el contrato de dominio que los consumidores externos (`jobs`, `payments`,
     `notifications`) deben usar `@TransactionalEventListener(phase = AFTER_COMMIT)` para
     garantizar que la transacción haya sido confirmada antes de disparar efectos secundarios.
   - No se incluye lógica de pagos dentro del módulo de cotizaciones.

7. **Rechazo explícito y alcance pendiente**:
   - Se implementó el caso de uso y endpoint `POST /quotations/{quotationId}/reject` para que el
     propietario pueda descartar una oferta sin aceptar otra.
   - **Criterios que quedan pendientes para fases posteriores**: creación del trabajo (`jobs`),
     notificaciones automáticas a las partes e historial de cambios de estado.
   - FR-UC-18 no se considera completado al 100% en esta fase y el issue permanece abierto.

8. **Fotografías y almacenamiento**:
   - `photoKeys` se mantiene como contrato provisional mientras se integra el PR #26.
   - No se publican claves en la bandeja ni se afirman URLs firmadas en OpenAPI.
   - El modelo de dominio queda preparado para enlazar con los futuros `mediaIds`/`evidenceIds`.

## Contrato expuesto

| Método | Ruta | Código éxito | Descripción |
| --- | --- | --- | --- |
| POST | `/requests` | 201 | Crear solicitud de reparación |
| GET | `/requests/me` | 200 | Listar solicitudes del usuario autenticado |
| GET | `/requests/open` | 200 | Bandeja resumen de solicitudes abiertas según especialidad del Fixer |
| GET | `/requests/{requestId}` | 200 | Detalle autorizado de la solicitud |
| POST | `/quotations` | 201 | Enviar cotización (Fixer verificado compatible) |
| GET | `/quotations/me` | 200 | Listar cotizaciones enviadas por el Fixer |
| GET | `/quotations/for-request/{requestId}` | 200 | Comparar ofertas recibidas (ordenadas por menor precio) |
| POST | `/quotations/{quotationId}/accept` | 200 | Aceptar oferta, rechazar competidoras y asignar solicitud |
| POST | `/quotations/{quotationId}/reject` | 200 | Rechazo explícito de una oferta por el propietario |

## Verificación automatizada

| Ejecución | Resultado |
| --- | --- |
| `.\mvnw.cmd clean verify` | BUILD SUCCESS; 122 pruebas, 0 fallos, 0 errores, 0 omitidas |
| `.\mvnw.cmd -Ppostgres-it verify` | BUILD SUCCESS; 89 pruebas de integración contra PostgreSQL 17 real, 0 fallos, 0 errores |
| Arquitectura (ArchUnit y Spring Modulith, 14 módulos) | Aprobado sin violaciones de fronteras ni ciclos cíclicos |
| OpenAPI (rutas, esquemas, enums y campos protegidos) | Aprobado (`OpenApiContractTest`) |
| Docker build (`docker compose build`) | BUILD SUCCESS; pruebas completas ejecutadas dentro de la imagen |

### Pruebas añadidas y suites de integración

- `RepairRequestTest`:
  - Ciclo de vida y validación de atributos.
  - Reglas de visibilidad: propietario, Fixer compatible durante OPEN, Fixer asignado tras aceptación, y administrador.
- `QuotationTest`:
  - Máquinas de estado de cotización (envío, aceptación, rechazo explícito y rechazo por aceptación competidora).
- `QuotationHttpContract` (suite base ejecutada en H2 con `QuotationContextTest` y en PostgreSQL 17 Testcontainers con `PostgresQuotationIT`):
  1. `unverifiedFixerCannotReadInboxOrDetail`: Fixer no verificado recibe 403 en bandeja y en detalle.
  2. `incompatibleFixerDoesNotReceiveNorReadRequest`: Fixer con especialidad incompatible no recibe la solicitud en bandeja ni puede consultar su detalle (403).
  3. `compatibleVerifiedFixerCanListAndReadDetail`: Fixer verificado y compatible recibe la solicitud y lee su detalle (200).
  4. `strangerCannotReadDetail`: Usuario ajeno sin relación recibe 403 sin filtración de información.
  5. `ownerCanQueryOwnResources`: Propietario consulta sus solicitudes y sus cotizaciones.
  6. `twoSimultaneousAcceptancesOnlyOneWins`: Dos aceptaciones simultáneas sobre ofertas de la misma solicitud; exactamente una gana (200), la otra recibe conflicto (409), una cotización queda ACCEPTED, una REJECTED y la solicitud ASSIGNED.
  7. `concurrentDuplicateQuotationGets409Never500`: Dos solicitudes simultáneas de cotización del mismo técnico sobre la misma solicitud; una genera 201 y la otra 409 con código `ALREADY_QUOTED`, nunca 500.
  8. `quotationOfAnotherOwnerReturns403`: Intento de aceptar o rechazar cotizaciones pertenecientes a solicitudes de otro usuario devuelve 403.
  9. `privateFieldsAbsentInSummaryResponse`: Comprueba que `OpenRequestSummaryResponse` no expone `ownerUserId`, `description`, `photoKeys` ni `assignedFixerUserId`.
  10. `coherentAcceptanceEventPublished`: Publicación de `QuotationAccepted` con identificadores coherentes de solicitud, cotización, propietario, Fixer y monto.
  11. `explicitRejectionByOwnerTransitionsToRejected`: Propietario rechaza cotización individualmente (200), y un segundo rechazo devuelve 409 `QUOTATION_NOT_SUBMITTED`.

## Pruebas HTTP reales contra contenedor Docker

Se levantó el entorno de desarrollo:
```powershell
docker compose -f compose.development.yml --profile database up -d --build
```
Servicios observados:
- `fixup-backend-backend-1`: Up (Tomcat en puerto 8080 expuesto en `127.0.0.1:8081`).
- `fixup-backend-database-1`: Up (healthy) (PostgreSQL 17 en `127.0.0.1:15433`).

Se ejecutó el flujo HTTP completo de extremo a extremo a través del puerto del contenedor:

| Paso del flujo | Método y ruta | HTTP observado | Resultado comprobado |
| --- | --- | --- | --- |
| 1. Health check | `GET /actuator/health` | 200 | `{"status":"UP"}` |
| 2. Provisionar Propietario | `POST /auth/bootstrap` + `POST /auth/select-role` | 201 / 200 | Usuario activo con rol `OWNER` |
| 3. Provisionar Fixer | `POST /auth/bootstrap` + `POST /auth/select-role` | 201 / 200 | Usuario activo con rol `FIXER`, perfil verificado en PostgreSQL |
| 4. Crear solicitud | `POST /requests` | 201 | Solicitud `PLUMBING` creada en estado `OPEN` |
| 5. Consultar bandeja abierta | `GET /requests/open` | 200 | Fixer recibe la solicitud compatible. **Verificado**: `ownerUserId`, `description`, `photoKeys` y `assignedFixerUserId` no están presentes en el resumen |
| 6. Consultar detalle | `GET /requests/{requestId}` | 200 | Fixer verificado obtiene detalle completo con descripción y evidencias |
| 7. Cotizar solicitud | `POST /quotations` | 201 | Cotización registrada en estado `SUBMITTED` |
| 8. Comparar ofertas | `GET /quotations/for-request/{requestId}` | 200 | Propietario lee la lista de ofertas recibidas |
| 9. Aceptar cotización | `POST /quotations/{quotationId}/accept` | 200 | Oferta pasa a `ACCEPTED` |
| 10. Consultar solicitud asignada | `GET /requests/{requestId}` | 200 | Estado cambia a `ASSIGNED` y `assignedFixerUserId` coincide con el Fixer |
| 11. Validar bandeja tras asignación | `GET /requests/open` | 200 | La solicitud asignada ya no aparece en la bandeja abierta |

## Compromisos y entrega

- Commits convencionales en inglés (`feat`, `fix`, `test`, `docs`).
- No se realizó merge a `develop`.
- No se modificaron fuentes de frontend.
- No se renumeró la migración Flyway `V3` (permanece como V3 hasta integrar PR #26 y PR #27).
- No se versionaron credenciales, secretos ni archivos temporales.
- El issue no se cierra automáticamente dado que los flujos de creación de trabajo (`jobs`), pagos, notificaciones e historial quedan para las fases sucesivas.
