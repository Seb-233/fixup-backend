# Identidad, autenticación y autorización

## Autoridades y límites

Auth0 confirma la identidad externa. Spring Security verifica la firma RSA RS256,
issuer exacto, audience, expiración, not-before y subject antes de ejecutar los
controladores. El subject no vacío es obligatorio; email y name son datos iniciales
opcionales y no autorizan acciones.

PostgreSQL determina UUID interno, roles y estado de cuenta. Ni `roles` ni `scope`
del JWT se convierten en privilegios internos. Tampoco se aceptan identidad, UUID,
estado o roles mediante el cuerpo de bootstrap.

La API es STATELESS. CSRF está desactivado porque la autenticación usa Bearer y no
cookies de sesión; Basic, formulario y logout de sesión están desactivados. CORS usa
orígenes configurados, métodos GET/POST/OPTIONS, encabezados Authorization/Content-Type/Accept
y no permite credenciales de cookies. Sus rechazos también producen JSON 403.

Las claves JWKS se consultan bajo demanda: el arranque y health público no requieren
que Auth0 esté accesible. Esto no significa que un token pueda validarse sin una clave
confiable. Configura siempre issuer y audience reales para la integración de un tenant.
El endpoint JWKS es configuración confiable del servidor y nunca se toma del JWT del cliente.

Referencia técnica: [Spring Security Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
Se conservan explícitamente los validadores estándar de issuer y tiempo al añadir
los de audience y claims obligatorios.

## Fronteras y datos

- `identityaccess.api`: CurrentActor, CurrentActorProvider, Role, UserStatus,
  AdministrativeRoles y el evento RoleGranted.
- `identityaccess.domain`: cuenta interna y políticas de rol, sin JPA ni SecurityContext.
- `identityaccess.application`: bootstrap, consulta, selección inicial y asignación administrativa.
- `identityaccess.infrastructure`: JPA y adaptación del JwtAuthenticationToken validado.
- `identityaccess.web`: contrato HTTP y transformación de respuestas.
- `fixers`: perfil propio, estado de verificación y política de elegibilidad.
- `shared.security`: controles técnicos JWT/CORS/HTTP, sin reglas de negocio ni dependencias de módulos de negocio.
- `shared.errors`: interfaz técnica pública para la representación de errores HTTP.

`users.auth0_subject` es único y no nulo. El email puede cambiar y no es una clave
de identidad. `user_roles` tiene clave compuesta para impedir duplicados y restricciones
CHECK para los valores permitidos. Los estados y timestamps también son obligatorios.
`fixer_profiles` posee su propia tabla y no comparte entidades JPA con identityaccess.

Roles: OWNER, TENANT, FIXER, REAL_ESTATE_MANAGER, PLATFORM_ADMIN.
Estados de cuenta: ACTIVE, SUSPENDED, DISABLED.
Verificación Fixer: PENDING, VERIFIED, REJECTED, SUSPENDED; es independiente del rol.

## Bootstrap y concurrencia

`POST /auth/bootstrap` admite ausencia de cuerpo o `{}`.
Una cuenta nueva se crea ACTIVE, sin roles y con UUID generado por el backend (201).
Una cuenta existente conserva sus datos iniciales y devuelve 200. SUSPENDED y DISABLED
reciben 403 incluso al repetir bootstrap.

La restricción única protege solicitudes simultáneas. Un intento de inserción que
colisiona se revierte en una transacción independiente; después se consulta la cuenta
que ganó la carrera. No se reutiliza una transacción marcada para rollback.

`GET /auth/me` resuelve CurrentActor desde SecurityContext y PostgreSQL.
Solo acepta JwtAuthenticationToken autenticado. Si falta la cuenta interna, responde
409 USER_NOT_PROVISIONED; el cliente debe ejecutar bootstrap. La respuesta no expone
externalSubject. Email y displayName pueden ser null cuando no vienen en el access token.

## Roles y verificación

`POST /auth/select-role`, cuerpo `{"role":"OWNER"}`, permite únicamente
OWNER, TENANT y FIXER. Una repetición no duplica roles. Un UUID adicional en JSON
se rechaza; el usuario objetivo siempre proviene de CurrentActor. Se pueden acumular
los roles permitidos. El bloqueo de la fila de usuario serializa solicitudes concurrentes
y evita perder una asignación al añadir otra.

La asignación de FIXER publica RoleGranted dentro de la transacción. Un listener
síncrono del módulo fixers crea el perfil PENDING en esa misma transacción. Una repetición
no reinicia una verificación ya existente.

`FixerEligibility.requireVerified(actor)` es el contrato que deben usar los futuros
casos de uso de trabajos. Exige cuenta activa, rol FIXER, identidad del perfil coincidente
y estado VERIFIED. PENDING, REJECTED y SUSPENDED deniegan la acción.

Los roles administrativos no se obtienen por autoservicio. `AdministrativeRoles.assignRole`
aplica `@PreAuthorize` consultando el actor actual en PostgreSQL, compara su identidad
con el actor del caso de uso y exige PLATFORM_ADMIN. Además aplica la política de dominio.
No basta una autoridad o un claim administrativo agregado al JWT, ni un CurrentActor
antiguo después de revocar el rol en la base.

No se publica un endpoint administrativo en esta entrega. La provisión del primer
administrador y el flujo de verificación de Fixers necesitan procedimientos administrativos
controlados; no se incluyen datos iniciales con privilegios ni accesos alternativos.

## Respuestas y errores

| Código | Semántica |
| --- | --- |
| 200 | Lectura, bootstrap existente o selección idempotente de rol |
| 201 | Cuenta creada por bootstrap |
| 400 | Cuerpo inválido, campo no admitido, rol desconocido o perfil inicial inválido |
| 401 | Token ausente, inválido, vencido o autenticación distinta de JWT |
| 403 | Cuenta inactiva, permiso insuficiente o rol no permitido por autoservicio |
| 409 | Cuenta no provisionada o conflicto de identidad que no puede resolverse |

No se utiliza 402 para autenticación o autorización.

```json
{
  "status": 401,
  "code": "UNAUTHENTICATED",
  "message": "Authentication is required",
  "path": "/auth/me"
}
```

```json
{
  "status": 403,
  "code": "ACCESS_DENIED",
  "message": "You do not have permission to perform this action",
  "path": "/auth/select-role"
}
```

Los errores no incluyen tokens, causas internas, valores de claims o datos de conexión.
La base de datos debe mantenerse disponible para resolver permisos vigentes.

## Contrato y exposición

El [JSON OpenAPI](openapi.json) documenta los tres endpoints, Bearer JWT, esquemas
Role/UserStatus y códigos de respuesta. `OpenApiContractTest` verifica y exporta el
contrato directamente desde el recurso Springdoc a `target/openapi.json`, sin abrir el endpoint HTTP; el snapshot de documentación se actualiza desde esa
salida revisada. OpenAPI es el contrato HTTP, no un orquestador de módulos.

Solo `/actuator/health` es público en la configuración base, además de OPTIONS.
Health no expone detalles ni componentes. `/auth/**` requiere autenticación y el resto
queda denegado, incluido `/v3/api-docs` también en `dev`. La generación interna del
contrato se conserva en desarrollo y pruebas. Swagger UI permanece desactivado.

Los ensayos con claves efímeras prueban el Resource Server y la autorización interna.
La integración real de login/renovación de Auth0 y el cliente frontend requieren un
tenant configurado y access tokens dirigidos a esta API; no se consideran demostrados
por esas pruebas sintéticas.

## Rutas definitivas y ejemplos curl

Esta entrega expone exclusivamente POST `/auth/bootstrap`, GET `/auth/me` y
POST `/auth/select-role`. No hay alias, redirecciones ni compatibilidad con las
rutas retiradas. OPTIONS conserva el tratamiento de preflight CORS existente.

Ejemplos Bash: define TOKEN con un access token válido sin guardarlo en Git.
BASE_URL puede ajustarse al puerto local (8081 en el entorno de validación).

```bash
BASE_URL=http://localhost:8081
curl -i -X POST "$BASE_URL/auth/bootstrap" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}'
curl -i "$BASE_URL/auth/me" -H "Authorization: Bearer $TOKEN"
curl -i -X POST "$BASE_URL/auth/select-role" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"role":"OWNER"}'
```

La prueba de regresión comprueba que las tres rutas retiradas no tienen handlers
registrados y que, incluso con JWT válido, reciben 403 por `anyRequest().denyAll()`.
Los códigos y cuerpos de las operaciones vigentes no cambian.
