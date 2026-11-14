# E2-S2 · Texto para el SAD v2: módulos, seguridad real y vista física

Tareas: [E2-S2](https://app.clickup.com/t/1dftm3c1b5d), subtareas «Actualizar SAD con módulos y seguridad real» y «Actualizar C4 y despliegue NBIG44/NBIG206».
Borrador del 14/11/2026. Hecho a partir de `develop` del backend (`d07a2fd`, incluye BG-02 del PR #53) y del frontend (`606bd57`). Las figuras están en `c4-despliegue/diagramas-c4-despliegue.html` (1, 3 y 11 a 14), y también en PNG y SVG en la misma carpeta `c4-despliegue/`. La figura 1 de aquí (contexto) corresponde a la Figura 2 del SAD.

Cada bloque dice **dónde va** en el SAD y **qué texto entra**. Lo marcado **[confirmar con Sebastián]** no pasa al SAD hasta que él lo valide (vista física = DEVOPS-09).

---

## 1. Sección 8.1: contenedores (reemplaza la tabla)

Figura 3 actualizada.

| Contenedor | Tecnología | Responsabilidad |
|---|---|---|
| Aplicación web PWA y Android | Angular 22, TypeScript, Service Worker; Capacitor 8 para Android. Componentes propios, sin Ionic | Interfaz por rol: menú, Inicio y pantallas distintos para propietario, arrendatario, técnico, inmobiliaria y administrador. La guarda de rol solo decide la navegación; la autorización real está en la API |
| Servidor web y proxy | nginx | Sirve la PWA (sin caché para `index.html`, `ngsw.json` y el manifiesto), agrega cabeceras de seguridad y reenvía `/api/` a la API. Así el navegador habla con un solo origen |
| API de aplicación | Java 21, Spring Boot 3.5, Spring Modulith | Monolito modular con 15 módulos de negocio. Es servidor de recursos OAuth2: valida el JWT de Auth0 y lee el actor y sus roles de su propia base |
| Base de datos | PostgreSQL 17 | Datos de todos los módulos en una sola base, con migraciones Flyway. Row-Level Security en 24 tablas, rol de aplicación `fixup_app` sin `BYPASSRLS` y triggers que vuelven inmutables los montos de pagos e ingresos |
| Almacenamiento de objetos | MinIO (API S3) | Fotos, documentos de identidad y portafolio en un bucket privado. El navegador sube y descarga directo con URL firmada; la API nunca recibe los bytes |
| Observabilidad | Grafana Alloy, Prometheus, Loki y Grafana **[confirmar con Sebastián la topología]** | Métricas y logs de la API, incluidos los avisos `WARN` de seguridad y de conciliación de pagos |

Cambia también el párrafo de 8.4 que dice «una réplica»: hoy Blue corre con una réplica del backend y una del frontend. Las 2 a 3 réplicas con HPA son parte de Green (iteración 2), que todavía no está desplegada.

## 2. Sección 9.1: módulos de la API (reemplaza la tabla)

La API tiene 17 paquetes bajo `com.fixup`: 15 módulos de negocio, `shared` (seguridad HTTP, errores, configuración de RLS) y `users`, que está reservado y no tiene lógica. Cada módulo es dueño de sus tablas y solo expone su paquete `api`. `ModularityTest` (Spring Modulith) y `LayerRulesTest` lo verifican en cada CI. La columna «Usa» sale de los imports de `develop` y omite `shared`.

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

**Párrafo de colaboración (reemplaza el del SAD 1.0):** los módulos colaboran por contratos públicos del paquete `api` y por eventos de Spring publicados dentro de la transacción. `identityaccess` publica `RoleGranted` y `fixers` crea el perfil del técnico. Al aceptar una cotización, `quotations` inicia el pago por transferencia con el contrato de `payments`. El trabajo se abre en la misma transacción en que la pasarela aprueba el pago (`PaymentApproved`). Al cerrar el trabajo, `jobs` libera el ingreso del técnico llamando a `payments` por su contrato público y no por un evento, para no formar un ciclo. Los avisos al usuario viajan como `NotificationRequested`. Ningún módulo lee tablas de otro; si `requests` necesita títulos de solicitudes para `quotations`, los entrega en bloque por su `api`.

**Nuevo en la entrega 2 (para «Estado»):** la conciliación del libro de pagos (`ReconcileLedger`, SAF-01) está en una rama sin fusionar. Si se fusiona antes del 17/11, la fila de payments agrega: «conciliación en ejecución cada 10 minutos y bajo demanda en `GET /administration/payments/reconciliation`, solo para PLATFORM_ADMIN».

## 3. Sección de seguridad: cómo está implementada hoy

Va en la sección de decisiones de seguridad o como subsección nueva «Seguridad implementada» después de 9.1. Cada fila apunta a código que se puede mostrar.

| Capa | Qué hace | Dónde está |
|---|---|---|
| Autenticación | Auth0 (OIDC). La API valida firma RS256, `issuer`, `audience` y vigencia antes del controlador. Las claves JWKS se piden de forma diferida, así que `/actuator/health` no depende de Auth0 | `shared/security/SecurityConfiguration`, `JwtValidation` |
| Los roles no salen del token | El convertidor de autoridades devuelve una lista vacía: ni los roles ni los scopes del JWT dan permisos. `CurrentActorProvider` busca el `subject` en `users` y `user_roles` en cada petición y exige estado ACTIVE. Si alguien tiene varios roles, cuenta el rol activo que eligió | `identityaccess` |
| Lista cerrada de rutas | Solo `/actuator/health` es pública. Todo prefijo de negocio exige autenticación y cualquier ruta no listada da `denyAll`. Swagger solo existe en los perfiles dev y demo, y `/demo/**` solo en demo | `SecurityConfiguration` |
| API sin sesión | Sin cookies de autenticación, sin formulario ni basic, `STATELESS` y CSRF apagado porque solo se acepta `Bearer` | `SecurityConfiguration` |
| CORS | Orígenes tomados de `fixup.cors.allowed-origins`, sin credenciales; solo se aceptan las cabeceras `Authorization`, `Content-Type`, `Accept` e `Idempotency-Key` | `SecurityConfiguration` |
| Autorización en el caso de uso | Cada caso de uso revisa el rol del actor (`hasRole(Role.X)`: unas 110 comprobaciones, la mayoría para PLATFORM_ADMIN y OWNER) y la propiedad del recurso. Las acciones del administrador usan además `@PreAuthorize` | `application/` de cada módulo |
| Row-Level Security (defensa en profundidad) | Cada transacción con actor publica `app.current_user_id` y `app.current_user_roles` con `set_config(..., true)` y hace `SET LOCAL ROLE fixup_app`, un rol sin `BYPASSRLS`. Las políticas filtran 24 tablas (solicitudes, cotizaciones, pagos, ingresos, chat, documentos, inmuebles, visitas, contratos de pago, calificaciones…) aunque el código olvide un filtro. Lo que una política oculta responde 404, para no revelar si el recurso existe | `db/migration-postgres/postgresql/V10` a `V42`, `RlsSessionTransactionManager`; probado en `PostgresRowLevelSecurityIT` (17/17) |
| Auditoría de accesos | Cada acceso bloqueado deja un log `WARN` en `com.fixup.security.audit` con el motivo (`DENIED_OR_NOT_VISIBLE`, `ACCESS_DENIED` o `RLS_POLICY_VIOLATION`), el método y la ruta, sin datos personales | `shared/security/SecurityAuditLog` |
| Archivos | Solo JPEG, PNG y WebP, con verificación de los bytes mágicos del objeto ya guardado (no del tipo que declara el cliente) y un máximo de 10 MB. URL firmadas: 15 minutos para subir y leer adjuntos, 5 minutos para el portafolio. El bucket es privado y el token de la API nunca viaja hacia el almacenamiento | `media/domain/MediaContentTypeValidator`, `RequestUploadTicket`, `PortfolioViewResolver` |
| Plata | `Idempotency-Key` en pagos y retiros, bloqueo de fila, CHECK y UNIQUE en la base, triggers de inmutabilidad (V40/V42) y RLS que solo deja insertar un ingreso con la comisión correcta. Detalle en el documento de SAF-01 | `payments` |
| Configuración obligatoria | La API no arranca si falta la URL de la base, el issuer, el audience, el JWKS, los orígenes CORS o las credenciales del almacenamiento | `SecurityConfiguration.requiredBackendConfiguration` |
| Cabeceras del frontend | nginx agrega `X-Frame-Options: SAMEORIGIN`, `X-Content-Type-Options: nosniff`, `Referrer-Policy` y niega los archivos ocultos | `fixup-frontend/nginx/nginx.conf` |
| Contenedor | El pod del backend corre como usuario 1000 sin root, sin escalada de privilegios y sin capacidades de Linux | `k8s/backend-deployment.yml` |
| Secretos | No hay secretos en el repositorio: CD los toma del entorno Production de GitHub y crea los Secrets de Kubernetes en cada despliegue. El CI del frontend rechaza archivos sensibles | `.github/workflows/cd.yml`, `frontend-ci.yml` |

**Brechas conocidas, con su dueño (para la sección de riesgos):**

- **Sin `Content-Security-Policy` ni `Strict-Transport-Security`** en nginx. Lo detectaría el escaneo OWASP que pidió el profesor. Atributo seguridad **[confirmar encargado]**.
- `X-XSS-Protection: 1; mode=block` está obsoleto; conviene quitarlo cuando se agregue la CSP.
- **Los jobs programados se duplican con varias réplicas** (`ContractExpiryScheduler`, `SlaCheckScheduler`, `VisitReminderScheduler`): `develop` no tiene bloqueo entre réplicas. El arreglo (`PostgresTaskLock` con advisory lock) está en la rama `fix/backend-multi-replica-readiness` de Sebastián, sin fusionar. Es bloqueante para pasar a 2 o más réplicas.
- **Los jobs sin actor dependen de que el usuario de la conexión no quede sujeto a RLS.** Si en la VM el usuario de la base no es dueño de las tablas, `FORCE ROW LEVEL SECURITY` también filtra a esos jobs **[confirmar con Sebastián]**.
- **Riesgo de BG-02:** con la configuración actual, un backend Green con una variable mal puesta podría ejecutar Flyway contra la base Blue. La auditoría del 07/11 propone una imagen sin Flyway y un Job de migración aparte.

## 4. Sección 11: vista física (texto para DEVOPS-09, lo valida Sebastián)

### 11.1 Desarrollo (figura 11)

`compose.development.yml` levanta PostgreSQL 17, MinIO y la API, todo publicado solo en `127.0.0.1`. El frontend corre con `ng serve` en el puerto 4200 y el emulador de Android llega a la API con `adb reverse`. El perfil `dev` usa PostgreSQL y Auth0 real. El perfil `demo` admite H2, carga la semilla de Colombia y habilita el ingreso de demostración.

### 11.2 Integración y entrega continuas (figura 12)

- **CI** (runners de GitHub, en cada push y PR). Backend: integridad del repositorio (las migraciones Flyway ya publicadas no se pueden cambiar ni repetir número), frescura de la rama frente a la base, `./mvnw -Ppostgres-it clean verify` con PostgreSQL real en Testcontainers y contrato OpenAPI. Frontend: chequeo de archivos sensibles, lint, pruebas unitarias, build y `cap sync` de Android.
- **CD** (solo cuando CI de `main` termina bien; un despliegue nunca se cancela a medias). Un runner self-hosted construye la imagen, la publica en `ghcr.io` con tres etiquetas (`sha-xxxxxxx`, `main` y `latest`), sincroniza los Secrets desde el entorno Production de GitHub y aplica los manifiestos de `k8s/` con `kubectl`. El backend fija la imagen por la etiqueta del SHA, que es inmutable, y espera el `rollout status`.
- **[confirmar con Sebastián]** en qué VM corre cada runner y si el `KUBE_CONFIG` del frontend apunta al k3s de NBIG206.

### 11.3 Producción: Blue operativo (figura 13)

| Nodo | Qué corre | Exposición |
|---|---|---|
| **NBIG206**: VM Ubuntu 22.04 de la universidad, `10.43.99.147`, k3s de un nodo | namespace `fixup`: `fixup-frontend` (Deployment de 1 réplica, nginx con la PWA) | Traefik Ingress con TLS en `10.43.99.147.nip.io`. Desde internet entra por Tailscale Funnel en `https://fix-rent.tailb7a45b.ts.net/` **[confirmar que el Funnel termina en NBIG206]**. nginx reenvía `/api/` a `http://10.43.98.238/` |
| **NBIG44**: VM de la universidad, `10.43.98.238`, k3s (API en `:6443`) | namespace `fixup` (**Blue**): `fixup-backend` (Deployment, RollingUpdate con `maxSurge: 1` y `maxUnavailable: 0`, readiness y liveness en `/actuator/health`), `postgres` y `minio` (StatefulSets con PVC). Además, namespace `observability` con Grafana Alloy | Traefik Ingress en el puerto 80 hacia `fixup-backend:8080`, alcanzable solo desde la red de la universidad. Las fotos se sirven por el endpoint público de almacenamiento (`FIXUP_STORAGE_PUBLIC_ENDPOINT`) **[confirmar cuál es]** |

Verificado el 14/11/2026: `https://fix-rent.tailb7a45b.ts.net/` responde 200 y `/api/actuator/health` responde `{"status":"UP"}`.

### 11.4 Producción: Green propuesto (figuras 13 y 14)

Green es el segundo ambiente para desplegar sin cortar el servicio (BG-02). Vive en el namespace `fixup-green` del mismo k3s de NBIG44, aislado de Blue:

- **Iteración 1A (hecha):** namespace, cuota `green-budget` (8 pods, 2 PVC, 4 Gi, requests de 1200m/3 Gi y límites de 2500m/5 Gi), ServiceAccount sin token montado y NetworkPolicy que niega todo por defecto. Solo permite DNS, el tráfico entre pods Green y la entrada de Alloy al puerto de gestión. Un pod canary comprobó que Green no llega a los servicios de Blue.
- **Iteración 1B (hecha, con evidencia pendiente):** PostgreSQL y MinIO Green con PVC propios de 2 Gi (`local-path`), Secrets solo de Green y bucket privado. Las ocho pruebas (lectura y escritura, bucket privado, conectividad entre pods Green, bloqueo hacia Blue, persistencia tras reiniciar y salud de Blue) tienen PASS operativo informado por el operador. La evidencia documental completa sigue pendiente.
- **Iteración 2 (propuesta, no desplegada):** backend Green de 2 a 3 réplicas con HPA y `verify-green.sh` (dos endpoints y 60 respuestas de `/readyz`). Antes, la auditoría pide: una imagen sin Flyway, migraciones solo con un Job separado que verifique la identidad de la base Green, credenciales que solo sirvan en Green y una prueba de V1 a V43 en una base desechable.
- **Cambio de tráfico (sin diseñar):** Green no tiene ruta de entrada. La propuesta natural es cambiar el destino del proxy `/api/` de NBIG206 de Blue a Green y dejar Blue intacto para volver atrás **[decisión de Sebastián]**.

### 11.5 Ambiente de demostración de octubre

El despliegue con Docker Compose por SSH en la VM (`fixup-vm.command`, sección 11.4 del SAD 1.0) fue el de la sustentación del 8 de octubre. **[confirmar con Sebastián si sigue activo o se retira del SAD]**; si se retira, la sección se resume en un párrafo histórico.
