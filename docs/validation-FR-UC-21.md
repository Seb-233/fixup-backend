# Validación FR-UC-21 — autenticación y autorización

Fecha local: 2026-09-17 (America/Bogota). Repositorio: Seb-233/fixup-backend.
Rama: `feature/fr-uc-21-authentication`.
Base actualizada: `develop`, commit `12d3eacfcf974e0c767c5d1fd61b080fbd99de12`.

## Alcance y decisiones

- Auth0 identifica al usuario; Spring Security valida RS256, issuer, audience, tiempo y subject.
- PostgreSQL controla estado y roles. Las autoridades del JWT no otorgan roles internos.
- CurrentActor desacopla los casos de uso del JWT y del controlador HTTP.
- Bootstrap es idempotente por subject único, también ante solicitudes concurrentes.
- La selección de roles bloquea la fila del usuario para no perder cambios concurrentes.
- FIXER crea un perfil separado PENDING; la política de elegibilidad exige VERIFIED.
- Los roles administrativos tienen un caso de uso protegido con @PreAuthorize y política de dominio; no hay endpoint público ni administrador precargado.
- Flyway crea users, user_roles y fixer_profiles. Hibernate valida el esquema.
- Health es público sin detalles. OpenAPI JSON solo se publica en dev; Swagger UI está desactivado.

## Verificación automatizada

| Ejecución | Resultado |
| --- | --- |
| `.\mvnw.cmd clean verify` final | BUILD SUCCESS; 45 pruebas, 0 fallos, 0 errores, 0 omitidas |
| `.\mvnw.cmd --batch-mode --no-transfer-progress -Ppostgres-it verify` | BUILD SUCCESS; 45 pruebas principales y 42 adicionales contra PostgreSQL 17 real |
| Maven dentro de la construcción Docker final | BUILD SUCCESS; 45 pruebas, 0 fallos, 0 errores, 0 omitidas |
| Arquitectura | ArchUnit y Spring Modulith aprobados; 14 módulos |
| OpenAPI | Bearer, tres rutas, respuestas, roles, estados y campos opcionales/nulos comprobados |

El conjunto HTTP se ejecuta con H2 en modo PostgreSQL y se repite con PostgreSQL real
mediante Testcontainers. Incluye JWT realmente firmados, firma/issuer/audience/expiración
inválidos, jwt() para escenarios MVC, cuentas suspendidas/deshabilitadas, aislamiento
entre usuarios, roles administrativos falsificados, revocación, CORS, bootstrap concurrente,
roles concurrentes y los cuatro estados de verificación del Fixer.

El primer intento con Testcontainers no finalizó mientras descargaba Ryuk.
Después de completar la descarga se repitió y terminó exitosamente; el intento
incompleto no se contó como aprobación.

La CI ejecuta `-Ppostgres-it clean verify` y conserva reportes Surefire/Failsafe,
cobertura y `target/openapi.json`.

## Docker y base de datos

Se ejecutaron:

```powershell
docker compose -f compose.development.yml down
docker compose -f compose.development.yml --profile database up --build -d
docker compose -f compose.development.yml --profile database ps
docker compose -f compose.development.yml --profile database logs backend
```

No se usó `down -v`. La imagen final se volvió a construir tras ajustar el esquema
OpenAPI para campos nulos.

Estado final observado:

- `fixup-backend-backend-1`: Up; aplicación iniciada y sirviendo HTTP.
- `fixup-backend-database-1`: Up (healthy), PostgreSQL 17.
- `flyway_schema_history`: versión 1, success=true.
- Volumen `fixup-backend_postgres-data` conservado.
- Backend en `127.0.0.1:8081`; PostgreSQL en `127.0.0.1:15433`.
- Los puertos alternativos están únicamente en el .env local para evitar servicios preexistentes.
- DATABASE_URL dentro del backend usa `database:5432`.
- Los contenedores y fuentes de infraestructura/frontend existentes no se modificaron.

Una comprobación HTTP durante la recreación devolvió conexión vacía (HTTP 000).
Después de completar el arranque se repitió: health 200, API sin token 401 y token
inválido 401. El intento durante la transición no se contó como una respuesta HTTP válida.
El JSON OpenAPI final respondió correctamente y contiene las tres rutas.

## Pruebas HTTP reales

Se verificaron 14 casos a través del puerto HTTP del contenedor:

| Caso | Esperado | Observado |
| --- | --- | --- |
| Health público | 200 | 200 |
| API sin token | 401 | 401 |
| Token malformado | 401 | 401 |
| Bootstrap con JWT firmado | 201 | 201 |
| Bootstrap repetido, mismo UUID | 200 | 200 |
| Usuario activo, roles iniciales vacíos | 200 | 200 |
| Selección OWNER | 200 | 200 |
| Autoasignación PLATFORM_ADMIN | 403 | 403 |
| Autoasignación REAL_ESTATE_MANAGER | 403 | 403 |
| UUID ajeno en el cuerpo | 400 | 400 |
| Selección FIXER | 200 | 200 |
| Issuer incorrecto | 401 | 401 |
| Audience incorrecta | 401 | 401 |
| JWT vencido | 401 | 401 |

Se comprobó además en PostgreSQL que el perfil del Fixer sintético quedó PENDING.
El usuario de prueba y sus filas asociadas se retiraron después.

Los JWT de esta validación se firmaron con claves efímeras en memoria y un servidor
JWKS temporal. Se retiró esa configuración al terminar; el contenedor final usa las
variables normales de .env. No se publicaron tokens, claves ni datos personales.

## Límites de la evidencia

No se verificaron login/renovación ni tokens de un tenant Auth0 real: issuer y audience
locales son placeholders. La prueba sintética demuestra la validación criptográfica
del Resource Server y las políticas internas, no una integración desplegada con Auth0.

El primer administrador y la verificación administrativa de Fixers requieren un flujo
controlado posterior. Los futuros casos de uso de trabajos deben invocar
`FixerEligibility.requireVerified`; esta entrega no implementa trabajos.

## Entregables y seguridad

- [Contrato OpenAPI revisado](openapi.json).
- [Decisiones y uso de autenticación](authentication.md).
- [Operación local](../README.md).
- .env y .tools permanecen ignorados; .env no tiene seguimiento.
- .env.example contiene únicamente valores de ejemplo.
- No se agregaron claves, certificados, tokens, volcados ni credenciales reales.
- No se modificaron fuentes del frontend ni del repositorio de infraestructura.
- No se realizó merge a develop ni a main.

## Archivos modificados o agregados

El inventario siguiente corresponde a esta entrega, respecto de la base develop.

- `.env.example`
- `.github/workflows/backend-ci.yml`
- `compose.development.yml`
- `docs/architecture/modular-monolith.md`
- `docs/authentication.md`
- `docs/module-rules.md`
- `docs/openapi.json`
- `docs/validation-FR-UC-21.md`
- `pom.xml`
- `README.md`
- `src/main/java/com/fixup/fixers/api/FixerEligibility.java`
- `src/main/java/com/fixup/fixers/api/FixerNotEligibleException.java`
- `src/main/java/com/fixup/fixers/api/FixerVerificationStatus.java`
- `src/main/java/com/fixup/fixers/application/RegisterPendingFixer.java`
- `src/main/java/com/fixup/fixers/application/VerifyFixerEligibility.java`
- `src/main/java/com/fixup/fixers/domain/FixerProfile.java`
- `src/main/java/com/fixup/fixers/domain/FixerProfiles.java`
- `src/main/java/com/fixup/fixers/infrastructure/FixerProfileEntity.java`
- `src/main/java/com/fixup/fixers/infrastructure/FixerProfileJpaRepository.java`
- `src/main/java/com/fixup/fixers/infrastructure/JpaFixerProfiles.java`
- `src/main/java/com/fixup/fixers/web/FixerErrorHandler.java`
- `src/main/java/com/fixup/identityaccess/api/AdministrativeRoles.java`
- `src/main/java/com/fixup/identityaccess/api/CurrentActor.java`
- `src/main/java/com/fixup/identityaccess/api/CurrentActorProvider.java`
- `src/main/java/com/fixup/identityaccess/api/Role.java`
- `src/main/java/com/fixup/identityaccess/api/RoleGranted.java`
- `src/main/java/com/fixup/identityaccess/api/UserStatus.java`
- `src/main/java/com/fixup/identityaccess/application/AssignAdministrativeRole.java`
- `src/main/java/com/fixup/identityaccess/application/BootstrapResult.java`
- `src/main/java/com/fixup/identityaccess/application/BootstrapUser.java`
- `src/main/java/com/fixup/identityaccess/application/ExternalIdentityProvider.java`
- `src/main/java/com/fixup/identityaccess/application/GetCurrentUser.java`
- `src/main/java/com/fixup/identityaccess/application/InternalAuthorization.java`
- `src/main/java/com/fixup/identityaccess/application/RoleAssignments.java`
- `src/main/java/com/fixup/identityaccess/application/SelectInitialRole.java`
- `src/main/java/com/fixup/identityaccess/application/UserLookup.java`
- `src/main/java/com/fixup/identityaccess/application/UserRegistration.java`
- `src/main/java/com/fixup/identityaccess/domain/ExternalIdentity.java`
- `src/main/java/com/fixup/identityaccess/domain/IdentityProblem.java`
- `src/main/java/com/fixup/identityaccess/domain/RolePolicy.java`
- `src/main/java/com/fixup/identityaccess/domain/UserAccount.java`
- `src/main/java/com/fixup/identityaccess/domain/UserAccounts.java`
- `src/main/java/com/fixup/identityaccess/infrastructure/JpaUserAccounts.java`
- `src/main/java/com/fixup/identityaccess/infrastructure/SecurityContextCurrentActorProvider.java`
- `src/main/java/com/fixup/identityaccess/infrastructure/SecurityContextIdentity.java`
- `src/main/java/com/fixup/identityaccess/infrastructure/UserEntity.java`
- `src/main/java/com/fixup/identityaccess/infrastructure/UserJpaRepository.java`
- `src/main/java/com/fixup/identityaccess/web/AuthController.java`
- `src/main/java/com/fixup/identityaccess/web/CurrentUserController.java`
- `src/main/java/com/fixup/identityaccess/web/IdentityErrorHandler.java`
- `src/main/java/com/fixup/shared/configuration/OpenApiConfiguration.java`
- `src/main/java/com/fixup/shared/errors/ApiExceptionHandler.java`
- `src/main/java/com/fixup/shared/errors/ErrorResponse.java`
- `src/main/java/com/fixup/shared/errors/package-info.java`
- `src/main/java/com/fixup/shared/security/JsonCorsProcessor.java`
- `src/main/java/com/fixup/shared/security/JwtValidation.java`
- `src/main/java/com/fixup/shared/security/RestAccessDeniedHandler.java`
- `src/main/java/com/fixup/shared/security/RestAuthenticationEntryPoint.java`
- `src/main/java/com/fixup/shared/security/SecurityConfiguration.java`
- `src/main/resources/application.yml`
- `src/main/resources/application-dev.yml`
- `src/main/resources/application-test.yml`
- `src/main/resources/db/migration/V1__identity_and_fixer_verification.sql`
- `src/test/java/com/fixup/integration/ApplicationContextTest.java`
- `src/test/java/com/fixup/integration/IdentityHttpContract.java`
- `src/test/java/com/fixup/integration/OpenApiContractTest.java`
- `src/test/java/com/fixup/integration/PostgresIdentityIT.java`
- `src/test/java/com/fixup/integration/TestJwtConfiguration.java`
