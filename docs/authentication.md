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

## BG-02 — hotfix de identidad: preparación del PR técnico

Estado: implementación congelada; revisión técnica contra main. No se ha creado
commit, push ni PR. No se autoriza merge, SQL productivo ni despliegue.

### Base y compatibilidad con main

Base, HEAD local y origin/main: `0f0e84d5255a939a68d066f6ff8adeaed21b1479`.
Rama: `fix/bg02-identity-runtime`. Se verificó por lectura de GitHub que main remoto
resuelve al mismo SHA; no se hizo fetch que modificara referencias, checkout,
merge ni acceso al worktree de develop. Esta comparación incluye los archivos
nuevos: git diff por sí solo no los mostraría.

| Área solicitada | Compatibilidad observada |
| --- | --- |
| RlsSessionTransactionManager | Solo el hotfix cambia su versión de main; no hay un cambio concurrente que conciliar. |
| IdentitySession/servicios | Las cuatro clases principales nuevas no colisionan con rutas de main. Los servicios modificados parten de la misma base. |
| SecurityConfiguration | Byte a byte idéntico a main; JWT, autorización de rutas y denyAll se conservan. |
| Migraciones | 41 históricas byte a byte idénticas, incluida V16_1; máxima V42. Sin V43/V44 ni otra versión asignada. |
| Pruebas | Tres archivos existentes adaptados y siete archivos nuevos de tests/fixtures. Sin cambios nuevos de main sobre esta base. |
| CD/CI y configuración | Cinco workflows idénticos a main. POM, manifiestos y configuración de aplicación no cambian. |

No se puede producir un conflicto de merge por avance de main en esta instantánea:
main y el ancestro del conjunto local son el mismo commit. No se hizo un merge de
ensayo ni se crearon commits sintéticos. Si main avanza, repetir esta comparación
antes de publicar/abrir el Draft. HEAD todavía no contiene el diff: las 25 rutas
están en el worktree. La apertura remota requiere autorizar después commit/push y
confirmar que la head publicada contiene exactamente el conjunto revisado.

### Causa, solución y fronteras de seguridad

El runtime Green NOINHERIT no tiene SELECT directo de users/user_roles. El flujo
AuthController → BootstrapUser → UserRegistration → JpaUserAccounts consultaba
users antes de construir el actor que habilitaba app. La resolución de actor vía
SecurityContextCurrentActorProvider → UserLookup tenía la misma dependencia;
PostgreSQL devolvía 42501. La membresía SET no otorga permisos por sí sola.

IdentitySession verifica el subject del JwtAuthenticationToken autenticado antes
de establecer DatabaseIdentityContext y abrir REQUIRES_NEW. Las consultas conservan
sus WHERE parametrizados; no aceptan roles SQL, subjects o callbacks desde el cliente.
Se rechaza cambiar de subject dentro de otra sesión de identidad. bySubject es
readOnly; byId y la revalidación de actor usan el contexto de negocio existente,
comprobando coincidencia de subject y estado. El seeder demo conserva su transacción
interna confiable y no entra en una sesión HTTP privilegiada sin JWT.

| Contexto de entrada | Rol local a la transacción |
| --- | --- |
| Actor resuelto | fixup_app + UUID/roles obtenidos de DB |
| Identidad autorizada sin actor | fixup_identity + subject autenticado |
| Sin ambos | Rol base sin elevación |

El decorador JpaDialect activa SQL después del begin delegado y antes de que Spring
enlace recursos. Se elimina el retorno permisivo por holder ausente del antiguo
override doBegin. En Spring ORM/TX 6.2.19, un begin normal mantiene el holder
preexistente o enlaza el nuevo antes de retornar; la corrección deja de depender
de esa búsqueda. Sin Session/activación no se ejecuta trabajo.

Ante failed-begin se preserva la excepción original: rollback, limpieza del estado
de dialecto y abort quedan como suppressed si fallan, evitando self-suppression.
Si rollback falla, se aborta la conexión física capturada. Spring sigue cerrando
el EM propio en su camino de failed-begin; no se invoca manualmente
`doCleanupAfterCompletion` ni se fabrica un status. Un EM externo preenlazado
conserva su propietario y puede reutilizarse. Se delegan readOnly, preparación,
JDBC, traducción de excepciones y finalización normal. H2 omite el SQL PostgreSQL.
Fuentes del ciclo: [JpaTransactionManager 6.2.19](https://raw.githubusercontent.com/spring-projects/spring-framework/v6.2.19/spring-orm/src/main/java/org/springframework/orm/jpa/JpaTransactionManager.java)
y [AbstractPlatformTransactionManager 6.2.19](https://raw.githubusercontent.com/spring-projects/spring-framework/v6.2.19/spring-tx/src/main/java/org/springframework/transaction/support/AbstractPlatformTransactionManager.java).

SET LOCAL y set_config(..., true) limitan rol/GUC al commit o rollback. El filtro
limpia actor e identidad antes y en finally después de cada solicitud, incluidos
bootstrap, JWT inválido y errores MVC; el interceptor limpia también su preHandle
fallido. No se activa identidad para schedulers sin principal. Estos trabajos
pueden seguir denegados con el rol base; no se amplían sus permisos aquí.

Las políticas identity SELECT son USING(true): el rol puede leer el directorio.
app.identity_subject no impone aislamiento SQL en esas lecturas. La frontera está
en los consumidores Java revisados y sus consultas. No se promete contención de
código servidor arbitrario o credenciales SQL comprometidas; no se endurecen esas
políticas reales en este hotfix.

### Herencia y administración

El preflight completo aportado por el usuario devuelve USAGE app solo para app y
fixup_green. Green es propietario de las dos tablas y tiene SUPERUSER/BYPASSRLS;
FORCE no limita sus operaciones actuales. Runtime es NOINHERIT, sin atributos de
bypass, con SET-only. identity y writer no heredan app. No hay incompatibilidad
administrativa actual ni se introduce una excepción para propietario/session_user.
Después de SET ROLE app, incluso ese login administrativo queda sujeto a la guarda.
[Semántica PostgreSQL 17](https://www.postgresql.org/docs/17/ddl-rowsecurity.html).

La propuesta aborta si aparecen herederos efectivos no privilegiados, incluidos
NOLOGIN/transitivos, o si writer hereda app. Un propietario futuro sin bypass
bajo FORCE puede sufrir bloqueo aunque otra política autorice administración:
se prueba esa condición y se exige revisión de privilegios mínimos, sin relajar
la autoasignación ni conceder writer al runtime. El inventario preparado distingue
INHERIT/SET y atributos. No monitoriza automáticamente cambios posteriores.

### Inventario final y exclusiones

25 archivos: 11 modificados y 14 nuevos frente a main. M=modificado; A=nuevo aún
sin tracking. No hay archivos staged. Esta fase modifica exclusivamente esta
documentación; las otras 24 rutas conservan su huella de entrada. Ninguna ruta de
main/develop, workflows o funcionalidades de pagos/conciliación/auditoría cambia.

| Estado frente a main | Archivo | Propósito |
| --- | --- | --- |
| M | `docs/authentication.md` | Contrato de autenticación, auditoría vigente, ficha SQL y preparación del PR. |
| A | `docs/pending-migrations/BG02/select_role_privileges.sql` | Propuesta sin versión: INSERT por columna y dos políticas; comprobaciones previas. |
| A | `ops/blue-green/bg02-identity-preflight.sql` | Preflight de solo lectura de roles, ACL, RLS e historial. |
| A | `ops/blue-green/bg02-role-inheritance-review.sql` | Inventario de solo lectura de herencia transitiva y atributos. |
| M | `src/main/java/com/fixup/identityaccess/application/DemoUserBootstrapService.java` | Compatibilidad del seeder demo con su transacción confiable, sin privilegios HTTP. |
| A | `src/main/java/com/fixup/identityaccess/application/IdentitySession.java` | Puerto de sesión autorizada por subject. |
| M | `src/main/java/com/fixup/identityaccess/application/UserLookup.java` | Resolución autenticada antes del actor y lectura por UUID bajo actor. |
| M | `src/main/java/com/fixup/identityaccess/application/UserRegistration.java` | Bootstrap dentro de la sesión de identidad establecida antes del begin. |
| M | `src/main/java/com/fixup/identityaccess/infrastructure/JpaUserAccounts.java` | Inserción de roles faltantes y UPDATE solo de updated_at; refresco JPA. |
| M | `src/main/java/com/fixup/identityaccess/infrastructure/SecurityContextCurrentActorProvider.java` | Subject del principal y revalidación por UUID, sin segunda conexión normal. |
| A | `src/main/java/com/fixup/identityaccess/infrastructure/TransactionalIdentitySession.java` | REQUIRES_NEW/readOnly, igualdad del subject, guardas anidadas y finally. |
| M | `src/main/java/com/fixup/identityaccess/web/DatabaseActorContextInterceptor.java` | Resolución previa a negocio y limpieza también ante preHandle fallido. |
| M | `src/main/java/com/fixup/shared/configuration/RlsSessionTransactionManager.java` | Activación transaccional en JpaDialect y recuperación segura de failed-begin. |
| A | `src/main/java/com/fixup/shared/security/DatabaseIdentityContext.java` | Alcance ThreadLocal léxico de autorización; no promete aislamiento SQL por subject. |
| A | `src/main/java/com/fixup/shared/security/DatabaseSessionContextCleanupFilter.java` | Limpieza en entrada y finally de todas las solicitudes. |
| M | `src/test/java/com/fixup/identityaccess/application/SelectInitialRoleTest.java` | Adaptación del constructor del lookup en regresión unitaria. |
| M | `src/test/java/com/fixup/integration/DemoSecurityContextTest.java` | Provisionamiento test-only del rol de identidad en PostgreSQL demo. |
| A | `src/test/java/com/fixup/integration/PostgresIdentityRuntimeIT.java` | 27 casos restringidos, SQL capturado, RLS, contextos y fallos del pool. |
| A | `src/test/java/com/fixup/integration/PostgresIdentityTestConfiguration.java` | Rol/ACL de identidad solo en bases de prueba; Flyway histórico intacto. |
| A | `src/test/java/com/fixup/integration/PostgresRoleInheritanceIT.java` | 8 casos de herencia, administración y FORCE RLS. |
| M | `src/test/java/com/fixup/integration/TestJwtConfiguration.java` | Importa provisionamiento de DB de pruebas; conserva JWT RSA efímeros. |
| A | `src/test/java/com/fixup/shared/configuration/RlsSessionTransactionManagerTest.java` | 7 casos de ciclo Spring, errores originales y EM externo. |
| A | `src/test/resources/db/bg02_identity_schema.sql` | Fixture conservador de Green; identity SELECT USING(true), sin INSERT app inicial. |
| A | `src/test/resources/db/bg02_role_review.sql` | Clúster efímero de simulación de atributos administrativos y propiedad. |
| A | `src/test/resources/db/bg02_runtime.sql` | Runtime efímero NOINHERIT/NOBYPASSRLS con SET-only. |

Los archivos son texto .java/.sql/.md, sin binarios ni enlaces simbólicos. Se revisó
el contenido, no solo los nombres: no se identificaron claves privadas, tokens o
credenciales productivas. Las contraseñas literales de tres fixtures son sintéticas
y solo se usan con Testcontainers. Los roles SUPERUSER/BYPASSRLS de simulación se
crean únicamente en el clúster de prueba administrativa; no son el runtime ni son
una propuesta de cambio de atributos. Las pruebas negativas pueden crear políticas
adversariales más permisivas solo para comprobar que la guarda sigue denegando.

Excluidos del diff: target/, reportes, JARs, cobertura, OpenAPI regenerado, logs,
parches de revisión, fichas exportadas y archivos temporales de /tmp. No se añade
.gitignore para ocultar cambios. Las exportaciones locales de esta preparación
están en /tmp/bg02-pr-preparation y no son nuevas rutas del repositorio.

### Diff resumido por componente

| Componente | Archivos | Resultado |
| --- | ---: | --- |
| Identidad | 5 | Puerto/sesión, bootstrap, lookup y revalidación del actor autenticado. |
| Contexto y limpieza | 3 | Scope ThreadLocal, filtro de entrada/finally e interceptor con error seguro. |
| Transacciones | 1 | Dialecto local y recuperación de failed-begin conforme al ciclo Spring. |
| Persistencia y demo | 2 | Roles aditivos + updated_at y compatibilidad del seeder; sin endpoint nuevo. |
| Pruebas/fixtures | 10 | Regresiones y simulaciones aisladas, sin cambiar producción. |
| SQL y documentación | 4 | Dos preflights de lectura, propuesta sin versión y este documento. |

### Dependencias de liberación: Java e imagen frente a SQL

J0 designa el código base 0f0e84d. J1 designa este hotfix. S0 es el estado Green
aportado; S1 añade exclusivamente la propuesta select-role. La imagen anterior real
no está identificada por digest/procedencia: no se supone que sea J0 sin evidencia.

| Orden/estado | Bootstrap y auth/me | Nueva asignación select-role | Evidencia/límite |
| --- | --- | --- | --- |
| J0 + S0 | Incidente 42501 antes de resolver actor | Falla en la misma resolución | Diagnóstico aportado y código base; no se arrancó una imagen anterior. |
| J1 + S0 (A/C) | Recuperación con identidad local, sin migración nueva | INSERT/RLS ausentes: 42501 y rollback; no afirmar código HTTP estable. Rol ya presente puede dar 200. | Ensayado con runtime restringido. |
| J0 + S1 (D) | SQL nuevo no da SELECT al runtime ni activa identidad: no repara el incidente | Sigue bloqueada por resolución; además merge JPA general no es compatible garantizado con UPDATE solo de updated_at | Análisis del código base; falta ensayo del digest anterior. |
| J1 + S1 (B) | Recuperadas | OWNER/TENANT/FIXER e idempotencia pasan | Ensayado en PostgreSQL efímero. |

A: UserRegistration/UserLookup, IdentitySession/TransactionalIdentitySession,
DatabaseIdentityContext, RlsSessionTransactionManager, CurrentActorProvider y
limpieza recuperan bootstrap/me con los privilegios/lecturas identity actuales.
No necesitan el nuevo INSERT app. B: el adaptador JpaUserAccounts acota la escritura,
pero una nueva asignación requiere además el GRANT y las dos políticas de S1.
UPDATE(updated_at) es una dependencia ya satisfecha, no una concesión de este SQL.

E: volver al digest anterior conserva físicamente tablas/columnas y datos, porque
la propuesta solo agrega ACL/políticas. Eso NO demuestra compatibilidad funcional.
No existe un rollback funcional validado hacia un binario sin la corrección de
identidad y con merge amplio. Mantener las políticas nuevas y elegir/ensayar un
digest de fallback que ya tenga sesión de identidad y escrituras acotadas. Si el
objetivo es exactamente J0, declarar servicio degradado/retorno del incidente; no
conceder UPDATE general, DELETE, BYPASSRLS, INHERIT o writer para hacerlo funcionar.

Orden futuro sugerido, sujeto a autorización operacional separada: liberar J1 con
S0 para recuperar autenticación, manteniendo la dependencia de select-role visible;
coordinar/ensayar S1 y habilitar asignaciones después. Un rollback SQL puede mantener
J1 y volver a S0 sin borrar usuarios, roles o perfiles. Un rollback binario debe
conservar un target compatible probado. No se ejecuta ninguna etapa ahora.

### Ficha de dependencia para reconciliación con develop

Ficha de dependencia BG-02 para reconciliación con develop

Estado: propuesta sin versión, no aplicada. Repositorio Seb-233/fixup-backend.
Base del hotfix: 0f0e84d5255a939a68d066f6ff8adeaed21b1479. Destino del PR: main.
Fuente: docs/pending-migrations/BG02/select_role_privileges.sql.
No se envía esta ficha al otro agente ni se modifica su rama/worktree.

Tablas: public.user_roles recibe privilegio por columna y dos políticas INSERT.
public.users se consulta para ACTIVE; no recibe DDL, columnas ni permisos nuevos.
Ningún cambio de datos ni de fixer_profiles; el listener FIXER usa sus permisos actuales.

Roles: fixup_app recibe solo INSERT(user_id, role). fixup_green_runtime mantiene
NOINHERIT/NOBYPASSRLS y SET-only en app/identity; sin permisos directos nuevos.
fixup_identity y fixup_identity_writer: sin cambios de permisos, políticas o membresías.
fixup_green: propietario, SUPERUSER/BYPASSRLS e INHERIT, sin cambios. Es el único
heredero efectivo adicional según el preflight aportado. SET ROLE app pierde ese bypass.

Políticas nuevas: roles_app_self_insert AS PERMISSIVE FOR INSERT TO fixup_app y
roles_app_self_insert_guard AS RESTRICTIVE FOR INSERT TO fixup_app. Misma condición:
user_id = app_current_user_id(), rol OWNER/TENANT/FIXER y cuenta propia ACTIVE.
La guarda se combina con AND; otra política permisiva no puede ampliar la autoasignación.
No se tocan roles_writer ni las lecturas de identity USING(true).

Requisitos existentes: PostgreSQL >=16 para las consultas de membresía; USAGE public,
EXECUTE public.app_current_user_id(), SELECT de users y user_roles, UPDATE solo de
users.updated_at y políticas de lectura/bloqueo que permitan la fila propia.
Ambas tablas deben conservar ENABLE/FORCE RLS. Revisar definición/owner/seguridad
invoker de la función, todas las políticas restrictivas/permisivas aplicables, ACL
efectivas/por columna/default y herencia transitiva. El SQL aborta ante privilegios
inesperados, writer que herede app o futuros herederos app no SUPERUSER/BYPASSRLS.
No conceder atributos ni membresías para hacer pasar esas comprobaciones.

Flyway: main contiene 41 migraciones históricas, máxima V42 e incluye V16_1.
Son byte a byte idénticas; sin V43/V44 ni versión nueva asignada. Obtener la serie
y checksums que resulten del trabajo independiente de develop y el historial
operacional antes de elegir un número. No reutilizar números ni editar checksums.
La configuración de Green conserva Flyway desactivado; no habilitarlo en el runtime.

Validación: 989 PASS (606 estándar +383 integración; 0 fallos/errores/omitidas).
27 casos con runtime restringido, 8 de herencia/administración y 7 de ciclo Spring.
SQL consecutivo capturado: dos INSERT user_roles y dos UPDATE solo updated_at por
secuencia, sin DML de roles en la repetición; no DELETE/UPDATE user_roles.
Matriz de orden binario/SQL documentada en docs/authentication.md.
Las imágenes vieja/nueva por digest no se ensayaron: el rollback binario sigue pendiente.

Estrategia futura de migración: revisar snapshot ACL/políticas y sus herederos, asignar
versión coordinada fuera de este hotfix, ensayar copia aislada, ejecutar todo el SQL
como propietario administrativo en una sola transacción con rollback ante error.
No ejecutarlo con credenciales runtime ni durante el arranque de réplicas.
Una colisión de nombres de política debe abortar para revisión; no reemplazar políticas
con DROP/IF NOT EXISTS a ciegas. Conservar snapshots y checksums de lo aplicado.

Rollback SQL: nueva compensación revisada, una transacción y control de concurrencia.
Verificar previamente que las ACL/políticas agregadas siguen siendo las de esta entrega.
Retirar únicamente INSERT(user_id, role) agregado a app y las dos políticas nuevas;
restaurar exactamente cualquier ACL previa capturada. Evitar una ventana sin guarda:
retirar permiso antes de retirar políticas y no retirar restricciones con otros caminos
permisivos/ACL efectivos sin revisar. No tocar writer, roles/atributos, ENABLE/FORCE,
SELECT/UPDATE(updated_at) previos ni datos/historial aplicados. El Java corregido puede
seguir recuperando bootstrap/me, con nuevas asignaciones deshabilitadas.

Rollback de imagen: conservar SQL nuevo y elegir un digest compatible ya validado con
IdentitySession y escrituras acotadas. El código base no ofrece ese fallback: devuelve
al incidente y puede requerir UPDATE más amplio por merge. No conceder privilegios
extra ni debilitar RLS para compatibilizarlo. Digest anterior y ensayo son bloqueos de
liberación, no requisitos para revisar el Draft técnico.

Precaución de liberación: el main actual tiene CD automático Green tras CI de push a
main. Mantener Draft/no merge hasta autorización operacional separada. No modificar CD
en esta entrega. No se crea commit, push, PR ni despliegue con esta ficha.

### Validación y límites de evidencia

La validación completa previa terminó con código 0/BUILD SUCCESS: 989 PASS, cero
fallos, errores u omitidas. Se verificaron los XML existentes en esta preparación;
no se reejecuta una suite de 989 casos por cambios exclusivamente documentales.
Se comprueba el congelamiento por huellas de todas las fuentes/fixtures/SQL.

| Grupo | Casos/suites | Resultado |
| --- | --- | --- |
| Estándar Surefire | 606 / 70 | PASS |
| Integración del perfil Failsafe | 383 / 24 | PASS |
| PostgresIdentityRuntimeIT | 27 | PASS, login restringido distinto del propietario y del usuario Flyway |
| PostgresRoleInheritanceIT | 8 | PASS, administración/atributos y herencia |
| RlsSessionTransactionManagerTest | 7 | PASS, ciclo Spring/errores/EM externo |

Bootstrap 201/200, me 200, desconocido 409, JWT ausente/inválido 401 y suspendido 403.
Se prueban los tres roles iniciales, secuencias, repetición, otros usuarios, roles
administrativos, actor ausente, DELETE/UPDATE denegados, ACL exactas y ENABLE/FORCE.
Dos INSERT de roles faltantes y dos UPDATE solo updated_at por secuencia; repetir
no escribe, sin DELETE/UPDATE de user_roles. Rollback de rol/perfil FIXER/timestamp.
Se inspeccionan ambas conexiones, contextos anidados, fallos de activación/rollback/
cleanup, siguiente transacción y EM ajeno. El séptimo test unitario cubre reutilizar
la misma excepción. La focalizada previa aprobó 41 casos antes de añadir ese caso,
incluido después en la suite completa. Nada de esto equivale a ensayo de rollback
de la imagen anterior por digest ni a una validación HTTP contra Green.

```bash
./mvnw -Ppostgres-it -Dtest=RlsSessionTransactionManagerTest -Dit.test=PostgresIdentityRuntimeIT,PostgresRoleInheritanceIT verify
./mvnw -Ppostgres-it -Dspring.test.context.cache.maxSize=4 clean verify
git diff --check
git diff --cached --check
git status --short --untracked-files=all
```

Las expresiones completas no aportadas de otras políticas y funciones se deben
comparar antes de aplicar SQL. El fixture usa restricciones conservadoras donde
no hay texto completo y las dos lecturas identity USING(true) confirmadas. La
simulación administrativa evalúa roles privilegiados por separado del runtime.
No hay prueba productiva, migración aplicada, CI remoto nuevo, imagen construida
para despliegue ni otro resultado operacional afirmado en esta preparación.

### Título y descripción preparados del PR

Título: **fix(auth): restore BG-02 identity bootstrap under restricted PostgreSQL runtime**

Base prevista: main. Head prevista: fix/bg02-identity-runtime. Estado: Draft.
Sin auto-merge. Texto preparado para copiar, no publicado:

El runtime PostgreSQL de Green es NOINHERIT y no tiene SELECT directo sobre users/user_roles. Bootstrap y la resolución inicial del actor consultaban esas tablas antes de ejecutar SET ROLE: POST /auth/bootstrap fallaba con SQLSTATE 42501 y auth/me no podía resolver la identidad. Este hotfix permite recuperar ambas rutas con el esquema actual y mantiene denegadas las transacciones sin actor ni autorización de identidad.

La asignación de un rol nuevo mediante select-role tiene una dependencia SQL separada: este PR conserva la propuesta fuera de Flyway y no la aplica. La corrección Java por sí sola no habilita esa asignación.

## Diseño

- IdentitySession valida el subject contra el JWT autenticado antes de abrir REQUIRES_NEW. UserRegistration y UserLookup.bySubject ejecutan allí sus consultas parametrizadas. Las revalidaciones con actor usan byId y comprueban el subject.
- JpaDialect activa SET LOCAL ROLE fixup_identity para identidad autorizada o fixup_app para actor resuelto. Sin ambos conserva el rol base. La activación ocurre antes de enlazar los recursos de Spring; sus fallos impiden ejecutar trabajo.
- Rollback/cleanup/abort no reemplazan el error inicial. Si rollback falla se aborta la conexión física; Spring conserva la responsabilidad de cerrar el EntityManager. Se respeta el EM externo preenlazado.
- El filtro limpia actor e identidad antes y en finally después de cada solicitud. Se rechaza cambiar de subject dentro de una sesión de identidad anidada.
- JpaUserAccounts añade solo roles faltantes y actualiza únicamente updated_at; evita el merge general de users y refresca la colección JPA. El evento FIXER sigue siendo síncrono y transaccional.
- El seeder demo conserva su transacción confiable sin activar identidad HTTP. H2 omite el SQL específico PostgreSQL.

## Evidencia de privilegios mínimos y dependencia SQL

Green ya tiene SELECT users/user_roles y UPDATE(users.updated_at) para app. Faltan INSERT(user_id, role) en user_roles y una política INSERT app. La propuesta docs/pending-migrations/BG02/select_role_privileges.sql añade solo ese GRANT por columna y las políticas roles_app_self_insert (PERMISSIVE) y roles_app_self_insert_guard (RESTRICTIVE). Ambas exigen usuario propio ACTIVE y OWNER/TENANT/FIXER; la guarda impide ampliar estas condiciones mediante otra política permisiva.

No concede UPDATE/DELETE/TRUNCATE de user_roles, permisos nuevos sobre users, atributos ni membresías. No modifica identity_writer ni las lecturas identity USING(true). Solo app y fixup_green tienen USAGE app en el preflight aportado; Green es SUPERUSER/BYPASSRLS y no sufre el supuesto bloqueo administrativo. SET ROLE app sí mantiene las restricciones, aun desde ese login. El SQL aborta ante futuros herederos no privilegiados.

No hay V43/V44 ni cambios en las 41 migraciones históricas. La propuesta debe coordinar versión/checksums con el trabajo paralelo de develop antes de convertirse en migración. La ficha completa está en docs/authentication.md, sección de preparación del PR.

## Validación local previa, conservada en esta preparación

989 pruebas aprobadas; 0 fallos, errores u omitidas: 606 estándar (70 suites) y 383 de integración (24 suites). Las fuentes, fixtures y SQL ejecutables quedan congelados; esta fase solo actualiza documentación y revisa el diff. No se presenta esta preparación documental como otra ejecución de las 989 pruebas.

| Grupo | Evidencia |
| --- | --- |
| Runtime restringido, 27 casos | PostgreSQL 17.11; login NOINHERIT/NOBYPASSRLS, sin SELECT directo y con SET-only en app/identity; conexión Flyway separada. |
| Bootstrap/auth/me | 201 nuevo, 200 repetido y me; 401 sin JWT/inválido, 403 suspendido y 409 identidad desconocida. |
| Autoasignación y RLS | OWNER/TENANT/FIXER, OWNER→TENANT, TENANT→FIXER, repetición sin escrituras; rechazo de roles administrativos, terceros y actor ausente incluso con política permisiva adicional. |
| Persistencia | Solo INSERT de roles faltantes, UPDATE(updated_at) y bloqueos FOR NO KEY UPDATE; sin DELETE/UPDATE de user_roles. Rollback conjunto de rol, perfil FIXER y timestamp. |
| Contexto/pool | Subject ajeno rechazado, limpieza HTTP y anidada, ambos miembros del pool limpios y próxima transacción válida tras fallos de activación/rollback/cleanup. |
| Herencia/administración, 8 casos | SUPERUSER/BYPASSRLS con FORCE, rechazo tras SET ROLE app, propietario futuro no privilegiado y herencia transitiva. |
| Ciclo Spring, 7 casos | Fallo cerrado sin Session, recuperación de begin, error original y suppressed, self-suppression, cierre por Spring y propiedad del EM externo. |

Comando de la validación completa previa:
./mvnw -Ppostgres-it -Dspring.test.context.cache.maxSize=4 clean verify

Informes: target/surefire-reports y target/failsafe-reports. Log local: /tmp/bg02-final-full.log. SQL capturado: target/bg02-consecutive-sql.txt. Estos artefactos son evidencia local, no archivos incluidos en el PR ni CI remoto ejecutado.

## Compatibilidad y riesgos

- main remoto se verificó mediante lectura de GitHub en 0f0e84d5255a939a68d066f6ff8adeaed21b1479, igual a origin/main y HEAD local. No hay divergencia ni colisiones de los archivos nuevos. SecurityConfiguration, workflows y migraciones son idénticos a main.
- Identity USING(true) permite lectura amplia al rol de identidad. La frontera por subject está en Java y las consultas de sus consumidores; no se promete un sandbox SQL ante credenciales comprometidas o código servidor arbitrario.
- El texto completo de otras políticas/funciones operacionales y el digest/procedencia de la imagen anterior deben contrastarse antes de una liberación. El fixture es conservador donde no se aportaron expresiones completas.
- REQUIRES_NEW necesita capacidad adicional si se invoca desde una transacción exterior. El scheduler sin actor mantiene el rol base y puede seguir denegado; no se le activan privilegios de identidad.
- La comprobación de herencia no monitoriza cambios posteriores: repetir el preflight si cambia el catálogo. La propuesta no habilita nuevos caminos administrativos bajo app.

## Plan de validación y rollback (no ejecutado ni autorizado aquí)

1. Mantener Draft y validar el diff real/CI al publicar la head cuando se autoricen commit y push. Repetir compatibilidad si main avanza. No mezclar develop ni sustituir sus workflows.
2. Revisar con DBA la ficha SQL y la versión futura. En entorno aislado que reproduzca las políticas reales, ensayar Java con SQL actual y SQL nuevo; incluir el digest exacto elegido para rollback.
3. Java con el esquema actual recupera bootstrap/me, pero una asignación nueva falla por INSERT/RLS; seleccionar un rol ya presente puede seguir devolviendo 200. El SQL por sí solo no repara la resolución de identidad del binario base.
4. El binario de 0f0e84d no es un rollback funcional validado: carece del contexto de identidad y usa merge JPA general. Las políticas nuevas no amplían UPDATE de users para hacerlo funcionar. La imagen anterior exacta no está identificada; no prometer compatibilidad sin ensayo.
5. Elegir para rollback funcional un digest validado que ya contenga recuperación de identidad y escrituras acotadas. Conservar las políticas/ACL nuevas y las cuentas/roles/perfiles creados; no relajar RLS para adaptar un binario incompatible.
6. Si se requiere compensar SQL, hacerlo mediante una migración nueva revisada y transacción administrativa: retirar solo el INSERT app agregado y las dos políticas nuevas si siguen idénticas a las capturadas, conservando el resto. Mantener el Java corregido recupera bootstrap/me y deja select-role degradado. No borrar datos ni editar migraciones aplicadas.

El workflow existente .github/workflows/cd-green-deploy.yml publica/despliega Green tras CI exitoso de un push a main. Un PR/fix-branch no satisface ese gate, pero mergear a main sí puede activarlo. Este PR no modifica workflows ni autoriza ese despliegue: NO-GO para merge mientras no exista decisión operacional separada.

### Veredicto y estado de Git

GO técnico para revisar/abrir un Draft después de autorizar commit/push y comprobar
que la head remota contiene exactamente este diff. No puede abrirse ahora un PR
útil con el diff en el worktree: HEAD es aún la misma base y nada se ha publicado.
NO-GO para merge/despliegue: CD automático de main y rollback binario/dependencias
operacionales requieren una decisión separada. No se modifica CD para esquivar
esa decisión. No se ha creado commit, push, PR, merge ni despliegue.

Estado conservado: rama fix/bg02-identity-runtime; HEAD/base/origin/main
0f0e84d5255a939a68d066f6ff8adeaed21b1479; 11 modificados, 14 nuevos, nada staged.
Se comprueban diff --check, diff --cached --check y los espacios de archivos nuevos.
