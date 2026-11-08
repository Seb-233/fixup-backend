# BG-02 — cierre documental de 1B y auditoría previa de Iteración 2

Fecha: 2026-11-07. Alcance: repositorio local y resultados comunicados por el operador. Este informe no autoriza un despliegue, migración, cambio de NetworkPolicy ni promoción de tráfico.

## Estado y evidencia de 1B

El operador informa que PostgreSQL y MinIO Green están desplegados en `fixup-green` y que las ocho comprobaciones se ejecutaron sobre NBIG44 con resultados de terminal satisfactorios. Se clasifican **PASS operativo (resultado aportado por el operador)**. Esta auditoría recuperó registros de Jobs MinIO y una línea base previa de Blue. Los registros respaldan **parcialmente** 1B-02 (escritura/lectura) y documentan fallos previos; no prueban por sí solos el carácter privado del bucket ni las otras siete aceptaciones completas. Cada fila conserva **PENDIENTE documental** para la aceptación completa. Sólo pasará a **PASS documental** cuando registros sanitizados, identificables y cotejados cubran todos sus criterios.

| ID | Prueba ejecutada en NBIG44 | Resultado comunicado | Estado operativo | Estado documental |
| --- | --- | --- | --- | --- |
| 1B-01 | PostgreSQL: autenticación, escritura y lectura sintética | Satisfactorio | PASS operativo | PENDIENTE documental |
| 1B-02 | MinIO: bucket privado, escritura y lectura | Satisfactorio | PASS operativo | PENDIENTE documental del bucket privado; PASS documental parcial de escritura/lectura |
| 1B-03 | MinIO: acceso anónimo HTTP 403 | Satisfactorio | PASS operativo | PENDIENTE documental |
| 1B-04 | Conectividad entre pods Green | Permitida | PASS operativo | PENDIENTE documental |
| 1B-05 | Green → Blue: tres servicios productivos evaluados | Bloqueada | PASS operativo | PENDIENTE documental |
| 1B-06 | PostgreSQL tras recrear pod, mismo PV | Datos conservados | PASS operativo | PENDIENTE documental |
| 1B-07 | MinIO tras recrear pod, mismo PV | Objeto conservado | PASS operativo | PENDIENTE documental |
| 1B-08 | Blue durante 1B | Saludable, sin reinicios observados | PASS operativo | PENDIENTE documental |

### Formato de índice de evidencias 1B

Custodiar los registros sanitizados fuera de Git. Un índice puede almacenarse como CSV/Markdown con estas columnas, una fila por prueba y artefacto:

```text
id_prueba | fecha_utc | nodo_contexto | origen_operador | comando_o_metodo_sanitizado | archivo_custodiado | sha256_archivo | expectativa | resultado_observado | verificador | fecha_cotejo_utc | estado_documental
1B-01    | PENDIENTE | NBIG44/default | operador | psql sintético (sin credencial) | PENDIENTE | PENDIENTE | autenticación y lectura correctas | informado satisfactorio | PENDIENTE | PENDIENTE | PENDIENTE
```

Para cada fila, cotejar contexto/nodo y hora; recursos, cuota, endpoints y PV antes/después; comandos y salidas sanitizadas de pruebas sintéticas, 403 y rutas de aislamiento; UID del pod antes/después y `spec.volumeName` idéntico; salud y reinicios Blue antes/después. Registrar hash SHA-256 del **archivo ya sanitizado** y firma/nombre del verificador. La conectividad transitoria del cliente MinIO requiere cronología y mensajes sanitizados para distinguir recuperación por reintento de fallo persistente. No copiar Secrets, tokens, URLs firmadas, dumps ni datos productivos al índice o al repositorio. Una fila sin archivo cotejado permanece PENDIENTE documental.

### Índice sanitizado de archivos recuperados

Rutas de origen bajo `/home/estudiante/fixup-green-evidence/`, conservadas **fuera de Git**. Los SHA-256 fijan la integridad de los bytes inspeccionados, pero no autentican quién capturó la salida ni sustituyen una firma del operador. No se copian los YAML/logs originales: `logs-before.txt` incluye detalles de conexión que requieren sanitización antes de archivarse para terceros. Los archivos se cargaron como YAML o texto; los UID de los pods coinciden con los UID de los Jobs propietarios y sus marcas de tiempo son internamente coherentes.

| ID | Archivo relativo | SHA-256 | Lectura sanitizada y correspondencia |
| --- | --- | --- | --- |
| EV-01 | `1b-minio-oom/job-failed.yaml` | `fddd08584f980674c97774c04184af4f2e45fc849e6484da6ab6f5a5bb286519` | Job Green fallido, 2026-11-07 23:51 UTC; recursos menores que manifiesto final |
| EV-02 | `1b-minio-oom/pods-failed.yaml` | `d5d4e63d6bbd1f795629dc7753c8fa4bce3a434e33bb69021b484723a9df6e6a` | Pod propiedad de EV-01, `OOMKilled`, código 137 |
| EV-03 | `1b-minio-oom/logs-failed.txt` | `0777af0347da0b71ac0180aa9b96d40659a987d056f61d429fd57a861c6cffc2` | Registra terminación `Killed`; no prueba escritura |
| EV-04 | `1b-minio-retry/job-before.yaml` | `79481737bcce22f0ce7372f45d5ce177bbc8ebac6814435b4e719e6f29cac2f4` | Job Green fallido, 2026-11-08 00:09 UTC; argumentos distintos del manifiesto final |
| EV-05 | `1b-minio-retry/pods-before.yaml` | `0e1e83c41e38454518f4188c473b31f7b76860a22238fbddf159149d9c53e3d4` | Pod propiedad de EV-04, código 1 |
| EV-06 | `1b-minio-retry/logs-before.txt` | `e531727e50db88f9451ac10658016ef93fe7c9db7f1c019c1676618d7410095a` | Error de conexión/alias MinIO; contenido bruto no se incorpora aquí |
| EV-07 | `1b-minio-retry/job-after.yaml` | `8b371b51e09ee6ba0f41c63efbf496043f5aea68ecd04ec842c6d0646e72e813` | Job Green completado, 2026-11-08 00:41 UTC; spec relevante idéntica a `1b-minio-smoke-write.yaml` actual |
| EV-08 | `1b-minio-retry/pods-after.yaml` | `d391d51c1db7dd6b09d07afeb37a935116d77c8539026d76417900721d9d58e9` | Pod propiedad de EV-07, completado con código 0 |
| EV-09 | `1b-minio-retry/logs-after.txt` | `67f439557f0e96e6fbb582c4eadd67ac26c2e236d5fd44a3c655c059fa2333b4` | Reintento 1/10 por conectividad; luego escritura, `mc stat`, lectura y `SUCCESS` sintético entre 00:40:57 y 00:41:03 UTC |

Archivo adicional expresamente relacionado: `/tmp/fixup-green-preflight.log`, SHA-256 `3796ec503306cb7a88c6840752465c50ba81ebf07d98a7e87faf29e5e12718bc`, modificado a las 17:22 UTC del 7 de noviembre. Contiene línea base de Blue saludable antes de los Jobs, no un control posterior a 1B. Los scripts `/tmp/fixup-green-preflight.sh` y `/tmp/fixup-green-1b-preflight.sh` son procedimientos, no resultados de aceptación; no se incorporan. La cronología recuperada es: línea base → fallo OOM → fallo de conexión → Job de escritura/lectura completado. Ningún archivo recuperado demuestra la privacidad del bucket, HTTP 403, conectividad entre pods Green, bloqueo hacia los tres servicios Blue, persistencia de PostgreSQL/MinIO tras recreación ni salud Blue posterior. Los resultados de terminal comunicados para esos puntos no se pudieron recuperar de las rutas inspeccionadas; permanecen PASS operativo informado y PENDIENTE documental.

El documento `BG-02-iteration-1B-operator.md` describe una **sesión local previa** sin acceso al API; su veredicto NO APTO corresponde a esa sesión, no al estado comunicado ahora. `BG-02-iteration-2.md` contiene otra fotografía anterior a 1A/1B. Ninguno de los dos confirma el estado vivo actual. El cierre **documental** queda PENDIENTE hasta cotejar los registros y el estado del clúster en una sesión autorizada de sólo lectura.

## Verificaciones locales de la consolidación

| Comprobación | Resultado | Estado |
| --- | --- | --- |
| Archivos YAML activos de `ops/blue-green` | Carga sintáctica con PyYAML; nombres y namespaces revisados | PASS local |
| Scripts shell de BG-02 | `bash -n` sin errores | PASS local |
| Credenciales literales obvias en archivos BG-02 activos | Búsqueda de clave privada, JWT y asignaciones de contraseña/token sin hallazgos | PASS local; no es un escaneo exhaustivo |
| Archivos de evidencia MinIO recuperados | Nueve archivos inspeccionados; hashes registrados, YAML/UID/cronología cotejados; Job final y logs respaldan escritura/lectura sintética | PASS documental parcial de 1B-02 |
| Evidencia de las otras aceptaciones completas | No recuperada de las rutas BG-02 inspeccionadas | PENDIENTE documental |
| Admisión Kubernetes y estado vivo | Sin comprobación en esta auditoría | INCONCLUSO |
| Pruebas Java/Maven | Java 21 y Maven Wrapper 3.9.11 disponibles; `mvn` global ausente. No se ejecutaron pruebas ni migraciones | PENDIENTE |

Los manifiestos 1B usan `fixup-green`, `green-runtime`, Secrets Green y Service DNS Green. La ruta **aplicable de 1B** descrita en el procedimiento es `00-foundation.yaml` (sólo si no existe ya), `network-canary.yaml` (1A), luego `1b-postgres-pvc.yaml`, `1b-minio-pvc.yaml`, `1b-postgres.yaml`, `1b-minio.yaml` y `11-minio-bootstrap.yaml`; los Jobs `1b-minio-smoke-write.yaml` y `1b-minio-smoke-read.yaml` se aplican sólo para pruebas autorizadas. `10-data.yaml` es **alternativa combinada**, no se aplica junto con los dos `1b-*.yaml` de StatefulSet/Service. La equivalencia de esos cuatro objetos se verificó por comparación de documentos YAML; las PVC explícitas tienen la misma spec que los `volumeClaimTemplates` y los nombres `data-postgres-0`/`data-minio-0`. La cuota fija 8 pods, 2 PVC, 4Gi, 1200m/3Gi requests y 2500m/5Gi limits. PostgreSQL y MinIO piden cada uno 100m/256Mi y limitan 250m/512Mi; bootstrap 50m/64Mi y 100m/128Mi; cada smoke 100m/128Mi y 250m/512Mi, con `backoffLimit: 0`, deadlines 180 s y TTL 3600 s (bootstrap: 300 s y 3600 s). Las referencias de Secret son `green-postgres-secret` y `green-minio-secret`; todas las cargas usan `green-runtime` sin token automontado. Las NetworkPolicy seleccionan pods del namespace Green: deny por defecto; permisos locales a backend/probe, DNS en `kube-system` y puerto de gestión desde Alloy en `observability`. La revisión de texto no halló credenciales literales, pero no sustituye un escáner de secretos ni una comprobación viva de CNI. `20-backend.yaml` y `verify-green.sh` pertenecen a la Iteración 2 y no se han desplegado.

## Inventario Git y propuesta de consolidación

Rama: `fix/backend-multi-replica-readiness`, HEAD `ba7eb64`; no tiene upstream configurado. `develop` sigue a `origin/develop` y aparece 42 commits detrás según las referencias locales. Commits recientes en la rama: `ba7eb64`, `12ba5df`, `79a7031`. El index ya contiene cambios ajenos: `Dockerfile`, `pom.xml` y `src/test/resources/db/contract-migrations/V44__notifications_idempotency_contract.sql`; **no usar el index existente para un commit BG-02** sin aislarlo. No se ha hecho commit ni push.

| Ámbito | Cambios detectados | Decisión |
| --- | --- | --- |
| BG-02 | `ops/blue-green/` completo, actualmente sin seguimiento | Selección explícita descrita abajo |
| CI/CD | `.github/workflows/cd.yml` | Excluir |
| Backend y empaquetado | `Dockerfile`, `pom.xml`, `k8s/backend-deployment.yml`, `k8s/backend-pdb.yml`, `ops/backend/`, `src/test/java/com/fixup/media/infrastructure/`, V44 de test | Excluir |
| Observabilidad | `ops/observability/verify-metrics.sh`, `docs/audits/nbig44-log01-log02-plan-2026-11-07.md` | Excluir |
| Otras auditorías y operaciones | resto de `docs/audits/`, `ops/package-a/`, `fixrent-ops-validation.patch` | Excluir |
| Evidencia local | `.evidence/` | Excluir: podría contener datos operativos sensibles |

**Archivos propuestos para el commit exclusivo BG-02 1A/1B** (rutas exactas):

```text
ops/blue-green/00-foundation.yaml
ops/blue-green/10-data.yaml
ops/blue-green/11-minio-bootstrap.yaml
ops/blue-green/1b-minio-pvc.yaml
ops/blue-green/1b-minio-smoke-read.yaml
ops/blue-green/1b-minio-smoke-write.yaml
ops/blue-green/1b-minio.yaml
ops/blue-green/1b-postgres-pvc.yaml
ops/blue-green/1b-postgres.yaml
ops/blue-green/network-canary.yaml
ops/blue-green/BG-02-iteration-1A-review.md
ops/blue-green/BG-02-iteration-1B-operator.md
ops/blue-green/BG-02-iteration-1B-closure-and-iteration-2-audit.md
```

Se excluyen exactamente los cinco `*.backup-*` de los Jobs de MinIO: copias temporales generadas. Se excluyen `ops/blue-green/scripts/recreate-minio-green.sh`, `recreate-postgres-green.sh` y `verify-postgres-persistence.sh`: fijan UID/PV y, en un caso, nombre de pod Blue de una ejecución concreta; no son procedimientos portables ni evidencia de ejecución. Se excluyen `20-backend.yaml`, `verify-green.sh`, `BG-02-iteration-2.md` y `README.md` del commit 1B: son propuestas/fotografías de la etapa futura o anteriores al estado actual; deben revisarse en un cambio de Iteración 2. Ninguna exclusión implica borrar archivos locales.

### Aislamiento propuesto para el commit (sin ejecutar)

Worktree **preparado** en `/tmp/fixup-bg02-iteration-1b`, rama `bg02/iteration-1b-close`, base `ba7eb64cb33b89c978b8d85028578b3388ebe9df`. Se copiaron byte a byte las 13 rutas enumeradas arriba, conservando permisos; no se copiaron directorios completos. Se prepararon sólo esas 13 rutas con `git add -- <lista explícita>`; nunca `git add .` ni `git add -A`. `git diff --cached --name-status` mostró 13 altas y ninguna otra ruta; `git diff --cached --check` pasó; `git diff --cached --stat` mostró sólo esos 13 archivos. `git status --short` mostró sólo esas 13 altas. `Dockerfile`, `pom.xml`, V44, evidencias y respaldos no están en ese índice. El índice principal sigue con sus tres archivos ajenos previamente preparados. No se ejecutaron commit, integración ni publicación.

Este método evita que el index actual, que ya contiene tres archivos ajenos preparados, sea una entrada implícita del commit. La copia explícita requiere revisar que el archivo final de este informe y los 12 restantes sean idénticos al inventario aprobado en el momento de crear el worktree.

Procedimiento reproducible de preparación usado, mostrado para revisión (detenerse antes de `git commit`): guardar exactamente las 13 rutas de la lista anterior, una por línea, en `/tmp/bg02-files.txt`; luego, desde la raíz del repositorio:

```bash
bg02_src="$PWD"
bg02_wt=/tmp/fixup-bg02-iteration-1b
git worktree add -b bg02/iteration-1b-close "$bg02_wt" ba7eb64
mapfile -t bg02_files < /tmp/bg02-files.txt
test "${#bg02_files[@]}" -eq 13
for bg02_file in "${bg02_files[@]}"; do
  mkdir -p "$bg02_wt/$(dirname "$bg02_file")"
  cp -p -- "$bg02_src/$bg02_file" "$bg02_wt/$bg02_file"
done
git -C "$bg02_wt" add -- "${bg02_files[@]}"
diff -u <(sort /tmp/bg02-files.txt) <(git -C "$bg02_wt" diff --cached --name-only | sort)
git -C "$bg02_wt" diff --cached --check
git -C "$bg02_wt" diff --cached --stat
git -C "$bg02_wt" diff --cached
git -C "$bg02_wt" status --porcelain
```

La rama/ruta ya existen; **no repetir** `git worktree add`. Si cualquier verificación posterior diverge, detenerse y no crear el commit. La preparación se realizó; el commit sigue PENDIENTE de autorización.

## Auditoría técnica para Iteración 2

| Área | Hallazgo | Estado |
| --- | --- | --- |
| Flyway y numeración | 42 migraciones de producción: 28 comunes y 14 PostgreSQL, versiones V1–V43 con V16_1; no hay duplicados. V22/V23 no están activas. V44 está sólo en recursos de test y ya preparado en el index ajeno | PASS estático; historial Green INCONCLUSO |
| Dependencias del esquema | V10 crea `fixup_app` y RLS; V38 crea rol global y función `SECURITY DEFINER`; V43 altera `notifications` y crea triggers. Requieren roles/privilegios de creación y esquema previo coherente | PENDIENTE prueba en DB Green desechable y cotejo de historial |
| Esquema real | La DB 1B sólo tiene evidencia comunicada de una tabla sintética. No consta `flyway_schema_history`, tablas de aplicación ni checksums vivos | INCONCLUSO |
| Riesgo Green → Blue en Flyway | `spring.flyway.enabled=true` y Flyway usa el datasource de `DATABASE_URL`; la ConfigMap Green apunta a PostgreSQL Green. El init container compara tres variables de ConfigMap, pero no inspecciona la configuración efectiva de Spring, `SPRING_DATASOURCE_URL`, `SPRING_FLYWAY_URL`, perfiles ni el destino real de la conexión. Si una sobreescritura apunta a Blue y la red lo permite, **Flyway puede migrar Blue al arrancar** | FAIL del control preventivo; ejecución accidental PENDIENTE |
| Perfiles | Config base obliga credenciales/issuer; `dev` y `demo` habilitan Swagger, `demo` activa seed y puede usar H2. El manifiesto Green no fija `SPRING_PROFILES_ACTIVE` | Riesgo; fijar perfil de Green sin defaults de demo/dev |
| PostgreSQL/MinIO | Secret `green-postgres-secret` alimenta DB; `green-backend-secret` esperado por backend aún no existe aquí. Endpoint S3 interno y bucket privado en ConfigMap; `public-endpoint` interno no serviría para navegador fuera del clúster | PENDIENTE configuración y prueba de URL pública privada |
| SA y NetworkPolicy | `green-runtime` sin automontaje de token. Egress a DNS, PG y MinIO Green; ingress 8080 desde pods Green `backend`/`probe` y 8081 desde Alloy en `observability`. No hay ingreso desde un proxy NBIG206 externo ni acceso al Service Green mediante port-forward cubierto por esa regla de ingreso | PENDIENTE diseño de entrada; no cambiar política sin autorización |
| Probes | Backend tiene startup `/livez` hasta 300 s, readiness `/readyz` cada 10 s con DB, liveness `/livez` cada 30 s. PostgreSQL y MinIO sólo readiness; `pg_isready` no prueba contraseña | PASS estático; comportamiento vivo PENDIENTE |
| Recursos y cuota | Datos: 200m/512Mi solicitados y 500m/1Gi de límite. Backend 2–3 réplicas: total 500–650m/1.5–2Gi solicitados y 1300–1700m/3–4Gi de límite. Cabe en CPU/memoria de `green-budget` en reposo; 2 PVC usan exactamente 2/2 y 4Gi/4Gi. Canary + tres Jobs simultáneos + tres backend serían 9 pods, por encima de 8 | PASS aritmético; saldo vivo PENDIENTE |
| HikariCP | `HIKARI_MAXIMUM_POOL_SIZE=4`, `MINIMUM_IDLE=1`; 3 réplicas permiten hasta 12 conexiones de backend, más Flyway/admin. Límite PG y sesiones actuales no verificados | PENDIENTE |
| Coordinación e idempotencia | `PostgresTaskLock` usa advisory transaction lock para cuatro tareas; media usa `SKIP LOCKED`; notificaciones usan `ON CONFLICT` sobre índice V43. Requiere integración en varias réplicas y migración V43 exitosa | PASS de diseño estático; eficacia PENDIENTE |
| Actuator | Puerto 8081, exposición de health/prometheus; `env` y métricas generales no expuestas. `/livez` y `/readyz` están públicos en 8080 por diseño. Protección de 8081 depende de NetworkPolicy y entrada privada | PASS estático; exposición viva INCONCLUSO |
| Maven/Dockerfile/tests | Java 21 y `./mvnw` 3.9.11 disponibles. Dockerfile empaqueta con tests omitidos; existen pruebas unitarias y PostgreSQL/Testcontainers, incluidas V42→V43, RLS y MinIO. No ejecutadas en esta auditoría. `pom.xml` tiene cambios ajenos ya staged | PASS disponibilidad; pruebas PENDIENTE |

### Configuración y precedencia que afecta a Flyway

| Ubicación exacta | Configuración / implicación |
| --- | --- |
| `src/main/resources/application.yml`, `spring.datasource` | `url: ${DATABASE_URL}`, `username: ${DATABASE_USERNAME}`, `password: ${DATABASE_PASSWORD}`; Hikari toma `HIKARI_MAXIMUM_POOL_SIZE` y `HIKARI_MINIMUM_IDLE` |
| `src/main/resources/application.yml`, `spring.flyway` | `enabled: true`; `locations: classpath:db/migration,classpath:db/migration-postgres/{vendor}`. Sin `spring.flyway.url/user/password` explícitos, Flyway usa el datasource principal |
| `ops/blue-green/00-foundation.yaml`, ConfigMap `green-config` | `DATABASE_URL=jdbc:postgresql://postgres.fixup-green.svc.cluster.local:5432/fixup_green` y Hikari 4/1; no contiene usuario ni contraseña |
| `ops/blue-green/20-backend.yaml`, Deployment | Backend recibe `green-config` con `envFrom`; `DATABASE_USERNAME`/`DATABASE_PASSWORD` proceden de `green-backend-secret`, aún no creado por estos manifiestos |
| `ops/blue-green/20-backend.yaml`, init container `validate-green-endpoints` | Compara `DATABASE_URL`, `FIXUP_STORAGE_ENDPOINT` y bucket recibidos de `green-config`. No recibe las credenciales del backend ni inspecciona la configuración efectiva del proceso Java. Su éxito no prueba la base a la que conectará Flyway |
| `src/main/resources/application-dev.yml`, `application-demo.yml`, `application-test.yml` | Perfiles específicos; `demo` admite H2 por defecto y activa seed por defecto; `dev`/`demo` habilitan OpenAPI. El Deployment Green no fija `SPRING_PROFILES_ACTIVE` |
| `src/main/resources/db/migration/V*.sql` | 28 scripts comunes de V1 a V43; V1 crea usuarios/roles/tablas iniciales, V9 crea chat, V41 cambia pagos y V43 depende de `notifications`, `repair_requests`, `jobs`, `cash_settlements` y otras tablas/funciones previas |
| `src/main/resources/db/migration-postgres/postgresql/V*.sql` | 14 scripts PostgreSQL intercalados, entre ellos V10 (rol `fixup_app` y RLS), V38 (rol `fixup_property_debt_reader` y función `SECURITY DEFINER`) y V42 (RLS financiero). V16_1 es una versión válida entre V16 y V17. V22/V23 no están activos |
| `src/test/resources/db/contract-migrations/V44__notifications_idempotency_contract.sql` | Sólo recurso de test y cambio ajeno ya preparado; no está en las ubicaciones Flyway de producción |

Según la [documentación de Spring Boot 3.5 sobre configuración externa](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html), las fuentes posteriores prevalecen sobre las anteriores: valores por defecto → archivos `application.yml` empaquetados y perfiles → archivos externos → variables del entorno → propiedades JVM → `SPRING_APPLICATION_JSON` → argumentos `--...` (con otras fuentes web/JNDI entre medias). Dentro de los archivos de configuración, un archivo de perfil externo puede prevalecer sobre uno empaquetado. `SPRING_CONFIG_LOCATION`, `SPRING_CONFIG_ADDITIONAL_LOCATION` e importaciones pueden cambiar los archivos leídos; `SPRING_PROFILES_ACTIVE`/`SPRING_PROFILES_INCLUDE` pueden activar otros perfiles. `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` y `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD`, `SPRING_FLYWAY_ENABLED` o sus equivalentes `-Dspring.*`, JSON y `--spring.*` pueden alterar el destino o ejecución. La [documentación de inicialización de Spring Boot](https://docs.spring.io/spring-boot/how-to/data-initialization.html) confirma que `spring.flyway.url` o `spring.flyway.user` pueden crear un datasource separado y que Boot llama a `Flyway.migrate()` al inicio. El orden exacto debe validarse también en el artefacto y línea de comando finalmente aprobados.

### Diseño preventivo para Iteración 2 (propuesta, sin implementación)

1. Construir una **imagen de backend Green sin Flyway ni módulo PostgreSQL de Flyway en el classpath**; `spring.flyway.enabled=false` queda como defensa adicional, nunca como única barrera, pues es sobrescribible. Comprobar el contenido real del JAR y una prueba de arranque que falle si aparece un bean Flyway o intenta DDL. Esto elimina el camino de migración automática en cada réplica.
2. Ejecutar migraciones sólo mediante una **imagen/Job de migración separado** y autorización específica, con Secret Green exclusivo y permisos de DDL sólo para ese Job. El usuario de runtime del backend debe carecer de DDL; revisar cómo V10/V38 conceden roles y privilegios antes de separarlos. No reutilizar credenciales Blue ni la imagen backend de producción.
3. Antes de `migrate()`, validar dentro del mismo proceso la configuración efectiva de Flyway y datasource y la identidad del servidor/base Green mediante un marcador de clúster creado durante el aprovisionamiento Green y cotejado con el valor esperado. Rechazar cualquier URL, host, nombre de base, perfil, `spring.flyway.*` o fuente de configuración no autorizada; comprobar `flyway_schema_history`/checksums y detener ante divergencia. Una comparación textual de la ConfigMap no basta.
4. Exigir como defensa independiente credenciales que sólo funcionen en PostgreSQL Green y egress efectivo únicamente hacia el pod PostgreSQL Green. Probar denegación hacia Blue por Service **y endpoints** antes de autorizar el Job. No cambiar NetworkPolicy en esta auditoría.
5. Validar V1–V43 en una PostgreSQL Green desechable y aislada, incluyendo roles V10/V38, V43 y compatibilidad con el esquema esperado. Sólo después solicitar autorización para una migración Green real, con captura previa del estado, ventana de aborto y verificación posterior. Desplegar el backend sin capacidad DDL únicamente después de esa puerta.

Ninguna barrera aislada —DNS, `initContainer`, variable `SPRING_FLYWAY_ENABLED=false` o `dry-run`— demuestra por sí sola que Blue sea inaccesible. El control objetivo es que el backend no posea código ni permisos de migración y que el único ejecutor de DDL esté restringido a Green, con identidad de base verificada antes del primer cambio.

`local-path` con dos PVC de 2Gi no ofrece HA ni reserva física garantizada. La conectividad del cliente MinIO fue transitoria según el operador y está mitigada en los Jobs con reintentos acotados, pero exige análisis de causa antes de usarla como señal de estabilidad. Revisar cumplimiento de licencias de las imágenes MinIO/cliente y de dependencias redistribuidas antes de publicar una imagen o paquete.

## Matriz de decisión y plan seguro

| Prioridad | Condición | Acción requerida | Estado |
| --- | --- | --- | --- |
| Bloqueante | Flyway puede apuntar a Blue por configuración efectiva no comprobada | Separar migración Green de arranque de réplicas; fijar URL/credenciales Green; rechazar overrides `SPRING_DATASOURCE_URL`/`SPRING_FLYWAY_URL`; comprobar identidad de servidor/base y `flyway_schema_history` en Green con acceso de sólo lectura antes de permitir DDL | FAIL control actual; PENDIENTE corrección |
| Bloqueante documental | Las ocho pruebas tienen PASS operativo informado; hay archivos cotejados sólo para escritura/lectura MinIO y línea base Blue previa, sin evidencia completa de aceptación ni estado vivo actual | Completar índice sanitizado y cotejar namespace, políticas, PVC/PV, Secrets por nombre, Blue y cuota mediante lectura autorizada | PENDIENTE documental |
| Bloqueante | Roles y esquema requeridos por V10/V38/V43 sin prueba en Green | Ejecutar migraciones sólo en PostgreSQL Green desechable bajo autorización separada; comparar V1–V43 y checksums; revisar privilegios sin usar Blue | PENDIENTE |
| Riesgo | Cuota de pods, capacidad del nodo y almacenamiento local | Retirar Jobs/canary temporales sólo con autorización y sin tocar PVC; medir CPU, memoria, swap, disco y cuota antes/durante; conservar datos Green | PENDIENTE |
| Riesgo | Auth staging, entrada NBIG206, MinIO URL externa y licencias | Definir credenciales de staging, ruta/TLS y política de exposición; revisar licencia de imágenes/dependencias; no activar tráfico Blue | PENDIENTE |

Secuencia propuesta: (1) cerrar evidencia 1B y cotejar estado vivo; (2) congelar versión de imagen y migraciones, revisar licencias y dependencias; (3) preparar configuración y Secret Green independientes, perfil fijo y guardas de URL efectiva; (4) validar migraciones y privilegios contra DB Green desechable, sin Blue; (5) revisar capacidad/cuota, probes, Hikari y exposición de gestión; (6) solicitar autorización explícita para una migración Green controlada y después para backend Green; (7) desplegar por etapas y verificar dos endpoints, balanceo, HPA, seguridad, fallos y salud Blue. Cada puerta debe detener el avance ante divergencia de contexto, destino de DB, Secret, política o estado Blue. Este documento sólo cubre la auditoría previa; ninguna etapa de ejecución se realizó aquí.
