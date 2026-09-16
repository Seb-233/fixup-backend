# FixUp Backend

Base BACK-000 de un monolito modular bajo `com.fixup`. Sin casos de uso, controladores, entidades, migraciones ni integraciones reales.

## Herramientas y dependencias

Java 21 LTS, Maven 3.9.11 mediante Wrapper, Spring Boot 3.5.16, Spring Modulith 1.4.13 y Springdoc 2.8.17. Incluye Spring Web, Security, OAuth2 Resource Server, Data JPA, PostgreSQL, Flyway, Bean Validation y Actuator; JUnit, ArchUnit, Testcontainers y JaCoCo 0.8.14 para pruebas. Las versiones transitivas las administran los BOM de Boot y Modulith.

Compatibilidad: [Spring Modulith](https://docs.spring.io/spring-modulith/reference/appendix.html) y [Springdoc](https://springdoc.org/v2/). Se eligió la línea Boot 3.5 con versiones estables compatibles y Java 21 LTS.

## Preparación local

Instalar un JDK 21 y configurar `JAVA_HOME` hacia el JDK, no un JRE. Verificar `java -version`. No se requiere Maven global. El primer uso del Wrapper requiere acceso a Maven Central.

Linux/macOS o Git Bash:

```bash
./mvnw clean verify
./scripts/start-local.sh
```

PowerShell:

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

El servidor inicia en el puerto 8080. Toda solicitud queda denegada (403); no hay login, API pública, Swagger ni endpoints de Actuator activos. Las pruebas base no requieren Docker, base de datos ni Auth0.

La autoconfiguración de datasource, JPA, Flyway, Resource Server y usuario generado está excluida explícitamente para BACK-000. Las propiedades de conexión usan variables sin valores por defecto. No se resuelven para conectarse en esta fase. CORS solo queda declarado, sin habilitar acceso entre orígenes.

Para preparar la configuración futura, copiar `.env.example` a `.env` y mantener solo datos locales ficticios. Spring Boot y los scripts **no cargan automáticamente** archivos `.env`; exportar las variables en la terminal cuando una fase posterior active integraciones. Compose sí utiliza `.env`.

## Docker opcional

Instalar Docker con soporte para contenedores Linux:

```bash
cp .env.example .env
docker compose -f compose.development.yml up --build backend
docker compose -f compose.development.yml down
```

Compose publica únicamente en loopback. PostgreSQL está preparado bajo el perfil opcional `database`:

```bash
docker compose -f compose.development.yml --profile database up -d database
```

El backend aún no se conecta a PostgreSQL. Al activarlo en una fase posterior, la URL dentro de Compose deberá usar el nombre de servicio `database`, no localhost. No hay tablas ni migraciones. El volumen conserva datos locales al ejecutar `down`.

## Estructura

```text
src/main/java/com/fixup/
  FixupApplication.java
  shared/{configuration,errors,events,security,utilities}
  identityaccess/  users/     fixers/       properties/
  media/           requests/ quotations/   jobs/
  notifications/   messaging/ payments/    contracts/  analytics/
    {api,application,domain,infrastructure,web}/
src/main/resources/
  application.yml  application-dev.yml  application-test.yml
  db/migration/.gitkeep
src/test/java/com/fixup/{architecture,unit,integration,e2e}/
scripts/{build,test,start-local}.sh
docs/module-rules.md
.github/workflows/backend-ci.yml
.mvn/wrapper/maven-wrapper.properties
Dockerfile  compose.development.yml  mvnw  mvnw.cmd  pom.xml
```

## Arquitectura y validación

Ver [reglas modulares](docs/module-rules.md). `ModularityTest` exige los 14 módulos y ejecuta `ApplicationModules.verify()`. ArchUnit impide acceso a persistencia desde web o API. Las reglas sobre paquetes todavía vacíos permiten ausencia de clases y se aplicarán al agregarlas. Las pruebas de contexto comprueban el arranque sin servicios externos y la denegación HTTP.

`./mvnw clean verify` compila, prueba, empaqueta y genera cobertura en `target/site/jacoco/index.html`. Los resultados JUnit están en `target/surefire-reports`. CI ejecuta lo mismo y publica ambos reportes. No se impone un porcentaje de cobertura artificial a esta estructura inicial.

## Seguridad y contribuciones

Solo se versiona `.env.example`, con valores ficticios. Nunca subir secretos, llaves, tokens, certificados privados, credenciales de servicios, dumps, datos personales o logs sensibles. No registrar contraseñas, tokens, Authorization, documentos, pagos, variables de entorno ni consultas con información sensible. `.gitignore` es una barrera inicial; revisar siempre el diff y los archivos preparados.

Si se expone un secreto: detener el trabajo, identificar el secreto y commit sin volver a mostrar su valor, informar y revocar/rotar la credencial. No reutilizarla ni intentar resolverlo borrando en otro commit. Esperar instrucciones antes de limpiar historia y volver a verificar el repositorio.

Trabajar en ramas; BACK-000 usa `chore/backend-project-structure` y PR a `main`. Sin push directo, force push, reescritura de historia, autoaprobación ni merge sin revisión del orquestador. Usar commits coherentes con prefijos `chore(backend)`, `chore(modules)`, `test(architecture)`, `ci(backend)` o `docs(backend)`.

Antes de revisión:

```bash
./mvnw clean verify
git status
git diff --check
git ls-files | grep -E '(^|/)\.env($|\.)|\.pem$|\.key$|\.p12$|\.pfx$|\.jks$|\.keystore$|service-account.*\.json$'
```

La última comprobación solo puede listar `.env.example`.

## Pendientes fuera de BACK-000

Aprobar contratos OpenAPI y ASR antes de activar endpoints, autenticación, persistencia, eventos o lógica de negocio. Definir allowlists de dependencias por módulo según contratos aprobados. Testcontainers queda disponible; sus pruebas reales se agregarán cuando exista persistencia autorizada. El cierre de BACK-000 requiere CI remoto exitoso y revisión humana del PR.
