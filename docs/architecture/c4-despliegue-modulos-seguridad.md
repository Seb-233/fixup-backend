# E2-S2 · Texto para el SAD v2: módulos, seguridad y vista física

Versión del 15/11/2026, corregida tras la revisión del PR #55.

**Fuentes:**
- `develop` del backend (`6094e9b`).
- `main` del backend (`0f0e84d`), que tiene los manifiestos Green, el CD automático de Green y el CD manual de Blue (PR #56, #58 y #60).
- `develop` del frontend (`1c6dd21`).
- El estado de Green que reportó operación el 15/11 en la revisión del PR.

Las figuras están en `c4-despliegue/diagramas-c4-despliegue.html` (1, 3 y 11 a 14), con sus PNG y SVG en la misma carpeta. La figura 1 de aquí (contexto) es la Figura 2 del SAD.

**Alcance de publicación.** Este texto es para un SAD distribuible, así que no trae direcciones IP, puertos de administración, *hostnames* exactos, rutas internas, usuarios del sistema ni nombres o valores de Secrets. Los nodos se nombran por su función:

| Identificador | Qué es |
|---|---|
| `VM-FRONTEND` | VM con k3s que sirve la aplicación web |
| `VM-BACKEND` | VM con k3s donde corren la API, los datos, Blue y Green |
| `API-GATEWAY` | Ingress de `VM-BACKEND` por el que entra la API |
| `SERVICE-GREEN` | Service interno que reparte entre las réplicas Green |

Los valores exactos solo van en la documentación operativa privada.

**Estados que se usan abajo:**
- **Verificado:** se comprobó en el código o en una prueba, y se dice cuál.
- **Reportado por operación:** evidencia del operador que no está versionada en el repositorio.
- **Riesgo abierto:** algo conocido que todavía no se resuelve.
- **Previsto:** decisión tomada que aún no se ejecuta.

---

## 1. Sección 8.1: contenedores (reemplaza la tabla)

| Contenedor | Tecnología | Responsabilidad |
|---|---|---|
| Aplicación web PWA y Android | Angular 22, TypeScript, Service Worker. Capacitor 8 en Android. Componentes propios, sin Ionic | Interfaz por rol: menú, Inicio y pantallas distintos para propietario, arrendatario, técnico, inmobiliaria y administrador. La guarda de rol solo decide la navegación; la autorización real está en la API |
| Servidor web y proxy | nginx | Sirve la PWA, agrega las cabeceras de seguridad y reenvía `/api/` a la API por la red privada, así el navegador habla con un solo origen |
| API de aplicación | Java 21, Spring Boot 3.5, Spring Modulith | Monolito modular con 15 módulos de negocio. Es servidor de recursos OAuth2: valida el JWT de Auth0 y lee el actor y sus roles de su propia base |
| Base de datos | PostgreSQL 17 | Datos de todos los módulos con migraciones Flyway. Row-Level Security forzada en 23 tablas. Los montos de pagos e ingresos no se pueden modificar (triggers) |
| Almacenamiento de objetos | MinIO (API S3) | Fotos, documentos de identidad y portafolio en un bucket privado. El navegador sube y descarga directo con URL firmada; la API no recibe los bytes |
| Observabilidad | Agente Grafana Alloy | Recolección de métricas y logs. Su estado actual y su destino están por verificar: este SAD no afirma que haya Prometheus, Loki o Grafana desplegados |

En la sección 8.4, el texto debe distinguir los dos ambientes:
- **Blue** atiende hoy el tráfico productivo con una réplica del backend.
- **Green** tiene dos réplicas con HPA (mínimo 2, máximo 4), pero todavía no recibe tráfico productivo.

## 2. Sección 9.1: módulos de la API (reemplaza la tabla)

La API tiene 17 paquetes bajo `com.fixup`:
- 15 módulos de negocio.
- `shared`, con la seguridad HTTP, los errores y la configuración de RLS.
- `users`, que está reservado y no tiene lógica.

Cada módulo es dueño de sus tablas y solo expone su paquete `api`. `ModularityTest` (Spring Modulith) y `LayerRulesTest` lo verifican en cada CI. La columna «Usa» sale de los imports de `develop` y omite `shared`.

| Módulo | Casos de uso | Tablas propias | Usa | Estado |
|---|---|---|---|---|
| identityaccess | FR-UC-21 | `users`, `user_roles` | — | Implementado |
| fixers | FR-UC-16, FR-UC-23 | `fixer_profiles`, `fixer_verification_documents` | identityaccess, notifications | Implementado |
| media | FR-UC-17 | `media_assets`, `fixer_portfolios`, `portfolio_pieces`, `media_deletion_jobs` | identityaccess, fixers | Implementado |
| properties | FR-UC-02, FR-UC-07, FR-UC-12 | `properties`, `property_work_history` | identityaccess, media, notifications | Implementado |
| contracts | FR-UC-14 | `rental_contracts` | identityaccess, properties, notifications | Implementado |
| requests | FR-UC-04, FR-UC-08, FR-UC-11 | `repair_requests`, `request_invitations` | identityaccess, properties, contracts, fixers, media, payments, notifications | Implementado |
| quotations | FR-UC-18 | `quotations` | identityaccess, requests, fixers, jobs, payments, notifications | Implementado |
| jobs | FR-UC-19 | `jobs`, `job_status_log`, `job_evidences` | identityaccess, requests, properties, fixers, media, payments, notifications | Implementado |
| payments | FR-UC-05, FR-UC-13, FR-UC-20, FR-UC-22 | `repair_payments`, `simulated_payment_attempts`, `fixer_earnings`, `payouts`, `cash_settlements`, `owner_debts` | identityaccess, properties | Implementado. Desde el PR #47 toda reparación nueva es por transferencia; el efectivo queda solo como histórico |
| analytics | FR-UC-13, FR-UC-15 | `market_indicator_snapshots` | identityaccess, properties, payments | Implementado. El proveedor sintético solo existe en el perfil dev |
| messaging | FR-UC-24 | `chat_messages` | identityaccess, requests, notifications | Implementado |
| notifications | FR-UC-10 | `notifications`, `user_devices` | identityaccess | Avisos dentro de la app implementados; el push no tiene proveedor (`NoopPushNotificationGateway`) |
| visits | FR-UC-03, FR-UC-27 | `technical_visits` | identityaccess, requests, quotations, jobs, notifications | Implementado |
| remodelings | FR-UC-01, FR-UC-26 | `remodeling_projects`, `remodeling_requirements` | identityaccess, properties, requests, fixers, jobs, payments | Implementado |
| ratings | FR-UC-09 | `service_ratings` | identityaccess, requests, jobs, fixers, properties, notifications | Implementado |

**Colaboración entre módulos (reemplaza el párrafo del SAD 1.0).** Los módulos colaboran por los contratos públicos de su paquete `api` y por eventos de Spring publicados dentro de la transacción:
- `identityaccess` publica `RoleGranted` y `fixers` crea el perfil del técnico.
- Al aceptar una cotización, `quotations` inicia el pago por transferencia con el contrato de `payments`.
- El trabajo se abre en la misma transacción en que la pasarela aprueba el pago (`PaymentApproved`).
- Al cerrar el trabajo, `jobs` libera el ingreso del técnico llamando a `payments` por su contrato público y no por un evento, para no formar un ciclo.
- Los avisos al usuario viajan como `NotificationRequested`.

Ningún módulo lee tablas de otro.

## 3. Seguridad: qué está verificado, qué falta y qué está previsto

Va como subsección nueva, «Seguridad implementada», después de 9.1.

| Capa | Qué hace | Estado y dónde se comprueba |
|---|---|---|
| Autenticación | Auth0 (OIDC). La API valida la firma RS256, el `issuer`, el `audience` y la vigencia antes del controlador | Verificado: `SecurityConfiguration`, `JwtValidation`, y en `JwtRejectionContractTest` (ASR-SEC-08, PR #59) |
| Los roles no salen del token | Ni los roles ni los scopes del JWT dan permisos. `CurrentActorProvider` busca al usuario en `users` y `user_roles` en cada petición y exige que esté activo; con varios roles cuenta el rol activo | Verificado en código (`identityaccess`) |
| Lista cerrada de rutas | Solo el chequeo de salud es público. Todo prefijo de negocio exige autenticación y lo demás se niega. Swagger y el ingreso de demostración solo existen en los perfiles de desarrollo y demostración | Verificado en código (`SecurityConfiguration`) |
| API sin sesión | Sin cookies de autenticación; solo se acepta `Bearer` | Verificado en código |
| Autorización en el caso de uso | Cada caso de uso revisa el rol del actor y la propiedad del recurso; las acciones administrativas usan además `@PreAuthorize` | Verificado en código (`application/` de cada módulo) |
| Row-Level Security (defensa en profundidad) | 23 tablas con RLS habilitada y forzada (solicitudes, cotizaciones, pagos, ingresos, chat, documentos, inmuebles, visitas, calificaciones…). Cada transacción con actor publica el usuario y sus roles en la sesión de PostgreSQL y cambia al rol de aplicación, que no puede iniciar sesión ni saltarse RLS. Una función de solo lectura con permisos por columna resuelve la deuda del propietario sin abrir las tablas. Lo que una política oculta responde 404 | Verificado: migraciones V10 a V42 y `PostgresRowLevelSecurityIT` |
| Auditoría de accesos | Cada acceso bloqueado deja un log `WARN` con el motivo, el método y la ruta, sin datos personales | Verificado: `SecurityAuditLog` y `BlockedAccessAuditCountTest` (ASR-SEC-07, PR #59) |
| Archivos | Solo JPEG, PNG y WebP, verificados por los bytes del objeto ya guardado, con un máximo de 10 MB. URL firmadas de vigencia corta (15 minutos para adjuntos y 5 para el portafolio). Bucket privado | Verificado en código (`media`) |
| Dinero | `Idempotency-Key` en pagos y retiros, bloqueo de fila, restricciones en la base, triggers de inmutabilidad y RLS que solo deja insertar un ingreso con la comisión correcta | Verificado en código y migraciones (`payments`, V40/V42) |
| Configuración obligatoria | La API no arranca si falta la base, el emisor y la audiencia de Auth0, los orígenes CORS o el almacenamiento | Verificado en código |
| Cabeceras del frontend | `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy` y `Cross-Origin-Opener-Policy` | Verificado en `develop` del frontend (PR #51) |
| Content-Security-Policy | Hoy está en modo *report-only*: informa, pero no bloquea | Previsto pasarla a modo bloqueante (SEC-04) |
| Contenedores | Sin root, sin escalada de privilegios y sin capacidades de Linux | Verificado en los manifiestos de Blue y Green |
| Secretos | No hay secretos en los repositorios: se guardan como Kubernetes Secrets y el CI del frontend rechaza archivos sensibles | Verificado en los flujos de CI/CD |
| Aislamiento de Green | NetworkPolicy que niega todo por defecto, credenciales propias de Green y un runner de despliegue que solo puede modificar el Deployment Green y tiene negado Blue | Reportado por operación. El permiso se comprueba en cada despliegue (PR #58 y #60) |

**Límites que el SAD debe explicar, sin detalles internos:**
- **Spring Modulith:** verifica las dependencias entre módulos en las pruebas, no en tiempo de ejecución. Todos los módulos comparten una base, y lo que separa sus datos es la convención de que cada uno use sus propias tablas.
- **Auth0:** solo autentica. Si Auth0 no responde no se puede iniciar sesión, pero los tokens vigentes siguen siendo válidos hasta que vencen.
- **RLS:** protege las tablas que la tienen (23), no todas. Los procesos sin actor dependen de cómo esté configurado el usuario de la conexión.
- **Almacenamiento S3:** una URL firmada que se filtre da acceso a un solo objeto hasta que vence.
- **NetworkPolicy:** aísla Green de Blue dentro del clúster, pero no protege si alguien entra a la VM.
- **Flyway por ambiente:**
  - En Blue, la API aplica las migraciones al arrancar.
  - En Green, Flyway está apagado en el Deployment (`SPRING_FLYWAY_ENABLED=false`), así que la base Green se migra con un procedimiento aparte. Ese procedimiento todavía no está documentado en el repositorio.

**Riesgos abiertos (para la sección 15):**
- **Content-Security-Policy y HSTS.** La CSP todavía es *report-only* y nginx no envía `Strict-Transport-Security`, porque el TLS termina antes de llegar a él.
- **Jobs programados con varias réplicas.** `ContractExpiryScheduler`, `SlaCheckScheduler` y `VisitReminderScheduler` no tienen bloqueo entre réplicas en `main` ni en `develop`. El Deployment Green define `FIXUP_SCHEDULING_ENABLED=false`, `FIXUP_RLS_STRICT` y `FIXUP_RLS_IDENTITY_ROLE`, pero ninguna de las tres aparece en el código de `main`. Si la imagen desplegada sale de `main`, esas variables no hacen nada y los jobs corren en las dos réplicas. Por confirmar contra la imagen desplegada.
- **Tolerancia a fallos sin demostrar.** Falta la prueba de perder un pod con tráfico continuo. Además, las dos réplicas Green corren en el mismo nodo, así que no hay alta disponibilidad si cae la VM.
- **Cuota de Green sin versionar.** La cuota `green-budget` se amplió en el clúster para soportar el HPA y el RollingUpdate, pero ese cambio todavía no está en el manifiesto.
- **Permiso de `patch` del runner de Green.** Permite cambiar cualquier campo del Deployment Green, no solo la imagen.

## 4. Sección 11: vista física

### 11.1 Desarrollo (figura 11)

`compose.development.yml` levanta PostgreSQL 17, MinIO y la API, todo publicado solo en la interfaz local del equipo. El frontend corre con `ng serve` y su proxy envía `/api` a la API local. El emulador de Android llega a la API con `adb reverse`. El perfil `dev` usa PostgreSQL y Auth0 real; el perfil `demo` carga la semilla de Colombia y habilita el ingreso de demostración.

### 11.2 Integración y entrega continuas (figura 12)

- **CI** corre en runners alojados por GitHub, en cada push y PR.
  - Backend:
    - Integridad del repositorio: las migraciones ya publicadas no se pueden cambiar ni repetir número.
    - Frescura de la rama.
    - Verificación completa con PostgreSQL real en Testcontainers.
    - Contrato OpenAPI.
  - Frontend:
    - Chequeo de archivos sensibles.
    - Lint, pruebas y build.
    - Validación de la imagen Docker.
    - Análisis pasivo con OWASP ZAP.
- **CD de Green (automático).** Corre cuando el `Backend CI` de un push a `main` termina bien.
  1. Un runner alojado por GitHub construye la imagen de ese commit exacto y la publica en GHCR.
  2. Se obtiene su **digest SHA-256**.
  3. Un runner restringido despliega en Green **por digest**, no por etiqueta. Ese runner no usa Docker ni los secretos de Blue, y su permiso en Kubernetes solo alcanza al Deployment Green.
  4. Antes de desplegar comprueba que el commit siga siendo el último de `main` y que no tenga permiso sobre Blue.
  5. Hace un RollingUpdate, verifica la imagen, la generación y que haya al menos dos réplicas listas.
  6. Si el rollout falla, intenta restaurar el digest anterior.
- **CD de Blue (manual).** Solo corre a mano desde `main` y sirve para recuperar Blue. Ya no se dispara solo después del CI.
- **Frontend:** el CD sigue automático tras el CI de `main`.

### 11.3 Producción (figura 13)

| Nodo lógico | Qué corre | Exposición |
|---|---|---|
| `VM-FRONTEND` (k3s de un nodo) | Aplicación web: nginx con la PWA (1 réplica) | Ingress con TLS. Desde internet se entra por el dominio público de Fix&Rent. nginx reenvía `/api/` al `API-GATEWAY` por la red privada |
| `VM-BACKEND` (k3s de un nodo) | **Blue** (namespace `fixup`): API con 1 réplica, PostgreSQL y MinIO con volumen persistente. **Green** (namespace `fixup-green`): API con 2 réplicas, PostgreSQL y MinIO propios. Además, el agente de observabilidad | `API-GATEWAY` (Ingress) hacia la API Blue, alcanzable solo desde la red privada. Green no tiene ruta de entrada productiva: `SERVICE-GREEN` es interno |

Hoy el tráfico productivo va a **Blue**.

### 11.4 Green (figuras 13 y 14)

Estado reportado por operación el 15/11/2026:

| Elemento | Estado |
|---|---|
| Backend Green | 2 réplicas listas; HPA con mínimo 2 y máximo 4 (70 % de CPU); PodDisruptionBudget `minAvailable: 1`; RollingUpdate con `maxSurge: 1` y `maxUnavailable: 0`. Declarado en `ops/blue-green/21-backend-production.yaml` y `30-autoscaling.yaml` (`main`) |
| Imagen | Fijada por digest SHA-256 y actualizada por el CD automático |
| Datos | PostgreSQL y MinIO propios de Green, aislados de Blue, con credenciales restringidas |
| Balanceo | `SERVICE-GREEN` reparte entre las dos réplicas, que aparecen como dos endpoints |
| Cuota | `green-budget` se amplió el 15/11 a límites de 6 CPU y 7 Gi de memoria, y solicitudes de 1600m de CPU y 4 Gi. Falta versionar ese manifiesto |
| Evidencia | Rollout correcto. 30/30 comprobaciones HTTP por *port-forward* y 10/10 a través del Service interno |
| Pendiente | La prueba de perder un pod con tráfico continuo. Las dos réplicas están en el mismo nodo, así que no hay alta disponibilidad ante la caída de la VM |

### 11.5 Conmutación y rollback

**Decisión (prevista, no ejecutada):**
- Cuando Green se valide y se apruebe, el tráfico productivo pasará **100 % a Green**, sin repartirlo entre Blue y Green.
- Blue quedará desplegado, sin tráfico ordinario, para volver atrás si hace falta.
- Lo único que se balancea es el tráfico entre las réplicas de Green.

**El tráfico productivo todavía no se ha conmutado a Green.** Mientras tanto, si un despliegue de Green falla, el CD intenta restaurar el digest anterior, y Blue se puede volver a desplegar a mano desde `main`.

### 11.6 Ambiente de demostración de octubre

La sección 11.4 del SAD 1.0 describe el despliegue con Docker Compose por SSH que se usó en la sustentación del 8 de octubre. **Por confirmar:** si se retira, queda como un párrafo histórico.

## 5. Trazabilidad con los requisitos no funcionales

| Requisito | Qué lo respalda en esta vista | Estado |
|---|---|---|
| **RNF-08** · Recuperación de réplica | Green con 2 réplicas, PDB `minAvailable: 1`, probes de salud y RollingUpdate sin indisponibilidad | Parcial. La configuración está aplicada, pero falta la prueba de perder un pod con tráfico continuo, y no hay alta disponibilidad entre nodos |
| **RNF-09** · Despliegue trazable | Imagen construida desde el commit exacto que pasó CI, etiqueta con el SHA completo, despliegue por digest, comprobación de que el commit sigue siendo el último de `main` y resumen con commit, digest y hora | Verificado en el workflow de CD de Green (`main`, PR #60) |
| **RNF-10** · Blue/Green y rollback | Ambientes separados por namespace, NetworkPolicy y credenciales; restauración del digest anterior si falla el rollout; Blue conservado para volver atrás | Parcial. Green está desplegado, pero la conmutación del tráfico está prevista y no ejecutada |
| RNF-02 · Caché ante conectividad limitada | No depende del despliegue | No queda demostrado en esta vista |

## 6. Validación documental

Antes de publicar se revisó todo `docs/architecture/c4-despliegue*`: este texto, el HTML con el código Mermaid y los SVG y PNG, que se regeneraron desde cero. Se buscaron con `grep`:
- Literales de IPv4, con una expresión de cuatro octetos.
- Dominios del túnel público y dominios comodín construidos sobre una IP.
- Puertos de administración.
- Los nombres concretos de Secrets, usuarios del sistema y etiquetas de runner que tenía la versión anterior.

**No hubo coincidencias.** La búsqueda incluyó también el texto y los metadatos de los PNG. Además, los PNG se revisaron a ojo, porque su texto va dibujado y no se puede buscar.
