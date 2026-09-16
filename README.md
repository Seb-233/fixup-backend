# FixUp Backend

## 1. Descripción

Backend de FixUp desarrollado como monolito modular con Java, Spring Boot y Spring Modulith. El repositorio organiza los componentes del sistema bajo el paquete raíz `com.fixup`.

## 2. Objetivo del backend

Proporcionar una base técnica segura y mantenible para los servicios de FixUp, con responsabilidades separadas por módulo y reglas arquitectónicas verificables.

## 3. Arquitectura

Cada módulo encapsula sus datos y reglas, y organiza su código en `api`, `application`, `domain`, `infrastructure` y `web`. La comunicación entre módulos utiliza contratos públicos de `api`; los efectos secundarios se coordinan mediante eventos. `shared` contiene elementos técnicos reutilizables.

Spring Modulith verifica fronteras y ciclos. ArchUnit restringe dependencias de persistencia y evita que `shared` dependa de módulos del negocio. Consulta la [arquitectura del monolito modular](docs/architecture/modular-monolith.md).

## 4. Tecnologías

- Java 21 LTS y Maven 3.9.11 mediante Maven Wrapper.
- Spring Boot 3.5.16 y Spring Modulith 1.4.13.
- Spring Web, Security y OAuth2 Resource Server.
- Spring Data JPA, PostgreSQL, Flyway y Bean Validation.
- Actuator y Springdoc OpenAPI 2.8.17.
- JUnit, ArchUnit, Testcontainers y JaCoCo 0.8.14.
- Docker, Docker Compose y GitHub Actions.

El archivo `pom.xml` define las dependencias y sus versiones; los BOM de Boot y Modulith administran las versiones transitivas.

## 5. Requisitos previos

Instalar JDK 21 y configurar `JAVA_HOME` hacia el JDK. Verificar `java -version`. No se requiere Maven global: el Wrapper descarga Maven desde Maven Central en su primera ejecución.

Docker con contenedores Linux es opcional para ejecutar Compose. Las pruebas base no requieren Docker, PostgreSQL ni Auth0.

## 6. Configuración local

Copiar `.env.example` a `.env` para preparar las variables locales. El archivo de ejemplo contiene únicamente valores ficticios y `.env` está excluido de Git.

Spring Boot y los scripts no cargan automáticamente `.env`; las variables deben exportarse en la terminal cuando se utilicen. Docker Compose sí carga ese archivo.

Los perfiles disponibles son `dev` y `test`. La configuración mantiene desactivadas las conexiones externas de datasource, JPA, Flyway y OAuth2 Resource Server. Las propiedades de conexión usan variables de entorno sin credenciales incorporadas. CORS está declarado como configuración, sin habilitar acceso entre orígenes.

## 7. Ejecución

Linux, macOS o Git Bash:

```bash
./scripts/start-local.sh
```

PowerShell:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

El servidor utiliza el puerto 8080. La configuración de seguridad deniega las solicitudes HTTP con 403. No hay login ni endpoints funcionales; Swagger y los endpoints de Actuator están desactivados.

## 8. Pruebas

```bash
./mvnw clean verify
```

En PowerShell, utilizar `.\mvnw.cmd clean verify`.

La verificación compila, ejecuta pruebas, empaqueta el JAR y genera cobertura. `ModularityTest` exige los 14 módulos y ejecuta `ApplicationModules.verify()`. Las pruebas de contexto comprueban el arranque sin servicios externos y la denegación HTTP.

Los resultados JUnit se encuentran en `target/surefire-reports/` y el reporte de cobertura en `target/site/jacoco/index.html`. GitHub Actions ejecuta la misma verificación y publica ambos reportes como artefactos.

## 9. Docker

Con `.env` preparado a partir del ejemplo:

```bash
docker compose -f compose.development.yml up --build backend
docker compose -f compose.development.yml down
```

Compose publica los puertos únicamente en loopback. El servicio opcional PostgreSQL se inicia con:

```bash
docker compose -f compose.development.yml --profile database up -d database
```

El backend tiene la conexión a PostgreSQL desactivada. Una conexión desde otro contenedor debe usar el nombre de servicio `database`, mientras que una conexión desde el host utiliza el puerto publicado. El volumen conserva los datos locales al ejecutar `down`. No se incluyen tablas ni migraciones de negocio.

## 10. Módulos

Los 14 módulos son `shared`, `identityaccess`, `users`, `fixers`, `properties`, `media`, `requests`, `quotations`, `jobs`, `notifications`, `messaging`, `payments`, `contracts` y `analytics`.

```text
src/main/java/com/fixup/
  FixupApplication.java
  shared/{configuration,errors,events,security,utilities}
  <module>/{api,application,domain,infrastructure,web}
src/main/resources/
  application.yml
  application-dev.yml
  application-test.yml
  db/migration/
src/test/java/com/fixup/{architecture,unit,integration,e2e}
scripts/
docs/architecture/
```

Los paquetes reservados se conservan mediante `package-info.java`, sin entidades ni servicios de negocio.

## 11. Documentación adicional

- [Arquitectura modular](docs/architecture/modular-monolith.md).
- [Reglas de módulos](docs/module-rules.md).
- [Guía de contribución](CONTRIBUTING.md).
- [Política de seguridad](SECURITY.md).

## 12. Contribución

Crear una rama temporal, validar los cambios y abrir un pull request hacia `develop`. Los cambios en ramas permanentes requieren revisión y CI exitoso. Consulta [CONTRIBUTING.md](CONTRIBUTING.md) para convenciones y validaciones.

## 13. Equipo

Proyecto desarrollado por el equipo de FixUp. La participación en el código puede consultarse en el [historial de contribuciones](https://github.com/Seb-233/fixup-backend/graphs/contributors).
