# FixUp Backend

Backend de FixUp construido como monolito modular con Java 21, Spring Boot y Spring
Modulith. Auth0 autentica la identidad externa; PostgreSQL mantiene las cuentas,
los estados y los roles internos. Este repositorio no contiene el frontend.

## Arquitectura

Los 14 módulos conservan sus fronteras y capas `api`, `web`, `application`,
`domain` e `infrastructure`. La identidad interna pertenece a `identityaccess`;
los perfiles y la verificación de trabajadores pertenecen a `fixers`.
Los otros módulos conservan su estructura para sus entregas correspondientes.

Spring Security valida Bearer JWT antes de los controladores. Los casos de uso reciben
`CurrentActor`, con UUID interno, subject, roles y estado resueltos desde PostgreSQL.
Los módulos de negocio no intercambian JWT y no confían en roles recibidos del frontend.

Consulta las [reglas modulares](docs/module-rules.md) y la
[arquitectura](docs/architecture/modular-monolith.md).

## Tecnologías y requisitos

- JDK 21; configurar `JAVA_HOME`. Maven 3.9.11 mediante Maven Wrapper.
- Spring Boot 3.5.16, Spring Modulith 1.4.13 y Spring Security Resource Server.
- PostgreSQL 17, JPA/Hibernate, Flyway y Bean Validation.
- Actuator, Springdoc OpenAPI, JUnit, ArchUnit, Testcontainers y JaCoCo.
- Docker con contenedores Linux y Compose V2 para el entorno y las pruebas PostgreSQL.

`pom.xml` mantiene las versiones. H2 se utiliza solo en pruebas, no como base del backend.

## Configuración

Copia `.env.example` a `.env`; permanece ignorado por Git. El ejemplo contiene
únicamente valores ficticios. Configura un issuer de Auth0 terminado en `/` y el
audience de la API para utilizar tokens de un tenant real. No se requiere Client Secret.

| Variable | Propósito |
| --- | --- |
| `DATABASE_URL` | JDBC PostgreSQL; en Docker debe usar `database:5432` |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | Acceso local a la base de datos |
| `POSTGRES_DB` | Base creada por el contenedor PostgreSQL |
| `AUTH0_ISSUER_URI`, `AUTH0_AUDIENCE` | Issuer y audience exactos del JWT |
| `CORS_ALLOWED_ORIGINS` | Orígenes separados por comas, por ejemplo `http://localhost:4200` |
| `POSTGRES_PORT`, `BACKEND_PORT` | Puertos publicados en el equipo; defaults 5432 y 8080 |

Las claves públicas se obtienen de `<issuer>.well-known/jwks.json` al validar un token.
`AUTH0_JWK_SET_URI` es una configuración opcional de servidor para una ubicación
confiable de JWKS; no proviene de solicitudes ni de encabezados del cliente.
En despliegues reales utiliza el endpoint HTTPS de Auth0.

Compose carga `.env`; Spring Boot y Maven no lo cargan automáticamente.
Para ejecutar desde el host, exporta las variables y cambia la URL JDBC a
`jdbc:postgresql://localhost:<puerto-publicado>/fixup`. Ese valor no sirve dentro del
contenedor backend. No publiques valores sensibles ni registros sin revisión.

## Inicio con Docker

PowerShell:

```powershell
Copy-Item .env.example .env
docker compose -f compose.development.yml --profile database up --build -d
docker compose -f compose.development.yml --profile database ps
docker compose -f compose.development.yml --profile database logs --tail 100 backend
```

En Bash utiliza `cp .env.example .env` para la copia inicial. No sobrescribas un
`.env` existente. Si un puerto está ocupado, cambia únicamente su variable local.

El backend espera al healthcheck de la base cuando se activa el perfil `database`.
Flyway crea `users`, `user_roles` y `fixer_profiles`; Hibernate valida el esquema.
Para usar una base externa, configura sus variables y arranca solo `backend` sin
el perfil `database`.

Detención segura:

```sh
docker compose -f compose.development.yml down
```

El volumen `postgres-data` se conserva. No se automatiza ningún borrado de volúmenes.
El entorno de desarrollo publica los puertos únicamente en `127.0.0.1`.

## API de identidad

| Método y ruta | Comportamiento |
| --- | --- |
| `GET /actuator/health` | Público; devuelve estado sin detalles sensibles |
| `POST /api/v1/auth/bootstrap` | JWT válido; crea la cuenta sin roles (201) o devuelve la existente (200) |
| `GET /api/v1/users/me` | Cuenta activa resuelta por subject; no expone externalSubject |
| `POST /api/v1/users/me/roles` | Asigna OWNER, TENANT o FIXER idempotentemente al usuario actual |

Los errores de autenticación son 401 y los de autorización 403, con JSON uniforme.
Una cuenta aún no provisionada recibe 409 al consultar `me`. Los cuerpos con campos
de identidad o privilegios no admitidos se rechazan con 400.

FIXER se crea con verificación PENDING. El rol solo no autoriza ejecutar trabajos:
los casos de uso deben invocar `FixerEligibility.requireVerified(CurrentActor)`.
No se implementa aquí el flujo de revisión de trabajadores ni los casos de uso de trabajos.

La asignación administrativa requiere el caso de uso protegido
`AdministrativeRoles` y un PLATFORM_ADMIN vigente en PostgreSQL. No existe un
endpoint de autoasignación administrativa ni una cuenta administradora precreada.

## OpenAPI y perfiles

- `dev`: JSON público en `/v3/api-docs` para desarrollo.
- Sin `dev`: documentación HTTP desactivada y rutas de documentación denegadas.
- Swagger UI permanece desactivado.
- `test`: base H2 aislada y configuración ficticia; las claves de prueba se generan en memoria.

El [contrato OpenAPI versionado](docs/openapi.json) permite preparar el cliente del
frontend. La [guía de autenticación](docs/authentication.md) explica los estados,
límites y respuestas. Los placeholders no constituyen una integración operativa con
un tenant real de Auth0.

## Pruebas

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd -Ppostgres-it verify
```

En Bash usa `./mvnw`. La primera orden ejecuta arquitectura, HTTP, seguridad,
concurrencia y contrato sobre H2, con las migraciones reales. La segunda repite el
contrato HTTP contra PostgreSQL 17 mediante Testcontainers y requiere Docker.
No se omiten las pruebas PostgreSQL si Docker no está disponible: ese perfil falla.

Las pruebas combinan `jwt()` de Spring Security Test con tokens RSA firmados,
incluyendo firmas inválidas, issuer/audience incorrectos y expiración. No necesitan
credenciales reales de Auth0.

Reportes: `target/surefire-reports/`, `target/failsafe-reports/` y
`target/site/jacoco/`. La prueba de OpenAPI exporta `target/openapi.json`.
GitHub Actions ejecuta `-Ppostgres-it clean verify` y publica los reportes.

## Contribución

Trabaja en ramas temporales desde `develop` y abre PR hacia esa base. No hagas merge
directo ni force push. Consulta [CONTRIBUTING.md](CONTRIBUTING.md) y
[SECURITY.md](SECURITY.md). La identidad del backend, la integración Auth0 del
frontend y los demás casos de negocio mantienen responsabilidades separadas.
