# INT-01: pasarela Wompi

FixUp cobra el servicio de reparación y las deudas de propietarios con **Wompi** (Colombia) en
ambiente sandbox. La pasarela simulada (FR-UC-22) sigue disponible; Wompi aparece en la pantalla
de pago solo cuando el backend tiene las cuatro llaves de prueba.

## Solo sandbox (fail-closed)

El alcance académico autoriza únicamente el sandbox de Wompi, también en la instancia pública de
demostración. No se mueve dinero real y no se ejecutan pruebas sobre cobros reales.

- El adaptador no conoce la URL de producción. Se habilita solo si las cuatro llaves son de prueba
  (`pub_test_`, `prv_test_`, `test_integrity_`, `test_events_`) y la API es
  `https://sandbox.wompi.co/v1`.
- Una llave de producción, una mezcla de ambientes o una URL de API externa distinta del sandbox lo
  dejan **apagado**: `GET /payments/wompi` responde `{"enabled":false}`, los demás endpoints de Wompi
  responden 503 `WOMPI_NOT_CONFIGURED` y ninguna petición sale hacia Wompi. El log dice qué regla
  falló, nunca el valor de las llaves.
- Una URL de API local (loopback) se acepta solo para las pruebas automáticas con un Wompi falso.
- Los eventos del webhook deben declarar `"environment":"test"`.
- El script `configurar-wompi.sh` también rechaza llaves de producción.
- El frontend oculta "Pagar con Wompi" si el backend informa un ambiente distinto de `sandbox`.

Verificado por `WompiPaymentGatewayTest` (llaves de producción y mixtas, URLs externas; un servidor
local cuenta las peticiones y no recibe ninguna) y `PostgresWompiDisabledIT` (el backend arranca con
llaves de producción y la API de producción: Wompi queda apagado y la pasarela simulada sigue
aprobando pagos).

## Configurar las llaves

```bash
./scripts/configurar-wompi.sh
```

El script pide la llave pública, la privada, el secreto de integridad y el secreto de eventos
(panel de Wompi → Desarrolladores). Valida los prefijos, comprueba las llaves contra Wompi y escribe
`.env.wompi` (permisos 600, ignorado por git), que Spring importa al arrancar desde esta carpeta.
Con `--env-file ruta/.env` copia además las llaves a otro archivo (Docker, VM). También se pueden
pasar como variables de entorno: `WOMPI_PUBLIC_KEY`, `WOMPI_PRIVATE_KEY`, `WOMPI_INTEGRITY_SECRET`,
`WOMPI_EVENTS_SECRET` y, opcional, `WOMPI_RETURN_ORIGINS`.

## Flujo

```
Pagador ──(1) Pagar con Wompi──▶ FixUp API ──(2) referencia nueva + firma SHA-256──▶ URL de checkout
   │                                                                                     │
   └──────────────(3) paga en checkout.wompi.co (tarjeta, PSE, Nequi)◀───────────────────┘
   │
   └──(4) Wompi lo devuelve a /payments/{id}?id=<transacción>
             └──▶ FixUp API ──(5) GET /v1/transactions/{id} con llave privada──▶ Wompi
                       └── aplica APPROVED / DECLINED / ERROR (PENDING: vuelve a consultar)

Wompi ──(6) POST /payments/wompi/events (checksum SHA-256)──▶ FixUp API ──(5) igual que arriba
```

| Paso | Protocolo | Seguridad |
|------|-----------|-----------|
| Checkout | HTTPS GET con parámetros firmados | `signature:integrity` = SHA-256(referencia + centavos + `COP` + secreto de integridad): el monto no se puede cambiar en el navegador |
| Verificación | REST/JSON, `Authorization: Bearer prv_…` | El resultado nunca se toma del navegador; se consulta a Wompi |
| Webhook | HTTPS POST JSON, `X-Event-Checksum` | Checksum con el secreto de eventos; luego se vuelve a consultar la transacción por su id |

Reglas que se cumplen siempre:

- Cada apertura del checkout crea una referencia nueva (`FXR-<pago>-<8 hex>`), porque Wompi no
  acepta una referencia repetida. La tabla `wompi_checkouts` guarda referencia, pago, monto en
  centavos y quién la abrió (RLS: solo el pagador vigente, por el monto exacto, mientras el pago no
  esté aprobado; no se modifica ni se borra).
- La transacción debe ser de una referencia de FixUp, del mismo pago, por el mismo monto y en COP.
- El resultado entra por el mismo camino que la pasarela simulada: intento inmutable en
  `simulated_payment_attempts` (instrumento `WOMPI`), ingreso retenido del técnico, apertura del
  trabajo, liquidación de deuda y notificaciones. La conciliación SAF-01 lo cubre sin cambios.
- Retorno y webhook son idempotentes: la llave de operación sale del id de la transacción, así que
  aplicar dos veces la misma transacción no duplica nada.
- Si una segunda transacción aprueba un pago ya aprobado, no se crea otro ingreso y queda un
  `ERROR` en el log ("Doble cobro en Wompi") para reembolsarla desde el panel de Wompi.

## Modelo de confianza

- **El navegador no decide nada.** Solo trae el id de la transacción; el estado, el monto, la moneda
  y la referencia se leen de la API de Wompi con la llave privada.
- **El webhook no corre como proceso sin aislamiento.** Después de validar el checksum (que debe
  cubrir `transaction.id`) y de releer la transacción en Wompi, el resultado se aplica con la
  identidad de quien abrió el checkout: sus roles vigentes se leen de la base, y aplican
  `requirePayer` y el RLS de su sesión (`SET LOCAL ROLE fixup_app`), igual que cuando él vuelve del
  checkout. Si ya no es el pagador vigente (por ejemplo, el propietario revocó la delegación), no se
  registra nada y la transacción aparece "Sin registrar" en la conciliación.
- **El webhook encuentra el checkout sin `BYPASSRLS`.** Cuando llega el evento todavía no hay actor, y
  las políticas de `wompi_checkouts` (con `FORCE ROW LEVEL SECURITY`) no muestran ninguna fila a una
  sesión sin actor. Para saber quién abrió el checkout no se abre SELECT a nadie: la función
  `app_wompi_checkout_opener(referencia)` (V47, `SECURITY DEFINER`, mismo patrón que V38) pertenece a
  un rol sin login, sin herencia, sin membresías y sin `BYPASSRLS`, que solo puede leer las columnas
  `reference` y `created_by_user_id`. La función devuelve únicamente el id de quien abrió el checkout y
  se niega si la sesión ya tiene un actor, así que un usuario no puede usarla para resolver referencias
  ajenas. El resultado se aplica después con ese actor y las políticas de siempre.
- **Qué responde el webhook.** 401 si la firma falta o no coincide. 200 si el evento se aplicó o si
  reintentarlo no cambia nada (referencia que no es de FixUp, pagador que ya no está vigente, monto o
  moneda distintos, transacción todavía PENDING): esos casos quedan en la conciliación. 503 si Wompi no
  responde o todavía no devuelve la transacción del evento, y 500 ante cualquier falla interna: en
  ambos Wompi reenvía el evento. Un pago que debía registrarse nunca se da por recibido con un 200.
- **La llamada HTTP a Wompi va fuera de la transacción de base de datos:** no se retiene una conexión
  mientras Wompi responde (timeouts de 3 s de conexión y 8 s de lectura). Si Wompi falla o tarda, la
  respuesta es 503 `PAYMENT_GATEWAY_UNAVAILABLE`, el pago no cambia y, en el webhook, el 503 hace que
  Wompi reenvíe el evento.
- **Idempotencia y concurrencia.** El registro bloquea el pago (`SELECT … FOR UPDATE`) antes de
  mirar si la transacción ya se aplicó; la llave de operación sale del id de la transacción y
  `gateway_transaction_id` es único. Retornos y webhooks duplicados o simultáneos crean un solo
  intento, un solo trabajo y un solo ingreso.
- **Cobro doble.** Si una segunda transacción aprueba un pago ya aprobado, no se crea otro ingreso;
  queda en el log y como "Cobro doble" en la conciliación, para reembolsarla desde el panel de Wompi.
- **RLS de `wompi_checkouts`.** Solo el pagador vigente (propietario o administrador inmobiliario con
  la delegación vigente) y la administración de la plataforma leen; solo el pagador vigente crea, a
  su nombre, por el monto exacto y con el pago sin aprobar; `fixup_app` no tiene UPDATE, DELETE ni
  TRUNCATE.

## Migraciones

`V45__wompi_checkouts.sql` (común), `V46__wompi_checkouts_rls.sql` y
`V47__wompi_checkout_opener_resolution.sql` (PostgreSQL) son aditivas: una tabla nueva, la ampliación
del CHECK de `simulated_payment_attempts` para aceptar `WOMPI`, y el rol y la función de resolución del
webhook. No se edita ninguna versión aplicada (la corrección del webhook va en V47, no en V46).

**Orden con la bitácora de estados (backend #57).** V43 y V44 son de #57, que debe fusionarse antes:
Flyway no aplica versiones menores después de una mayor (`outOfOrder` está apagado). La secuencia final
es V43 → V44 (auditoría) → V45 → V46 → V47 (Wompi). Al rebasar esta rama sobre #57 solo hay que
ajustar cuatro conteos de versiones en pruebas (`PostgresRentalContractIT`,
`PostgresDigitalPaymentMigrationIT`, `PostgresFinancialStabilizationIT` y
`PostgresPropertyOwnerDebtIT`), que pasan a incluir 43 y 44.

Verificado con migración limpia (todas las pruebas con Testcontainers), con la actualización de una
base poblada en V40 hasta la última versión más `flyway validate`
(`PostgresDigitalPaymentMigrationIT`) y con la lista exacta de versiones activas
(`PostgresRentalContractIT`). Las mismas pruebas se ejecutaron sobre la combinación local de
`develop` + #57 + esta rama (V43 a V47 en instalación limpia y en actualización).

V47 la aplica el usuario de migraciones (crea un rol y cambia el dueño de una función, igual que V38);
el usuario de ejecución del backend no necesita DDL.

## Despliegue y rollback (Blue/Green)

- **Apagar Wompi no requiere desplegar:** basta quitar las llaves (o dejarlas de producción, que
  se rechazan) y reiniciar. Los pagos existentes y la pasarela simulada siguen igual
  (`PostgresWompiDisabledIT`).
- **El esquema es compatible hacia atrás:** la versión anterior arranca con V45, V46 y V47 aplicadas
  (Flyway ignora las migraciones futuras por omisión e Hibernate valida solo sus tablas).
- **Volver a la versión anterior es seguro solo mientras no haya intentos Wompi.** Esa versión no
  conoce el instrumento `WOMPI` y fallaría al leer esos intentos (JPA `EnumType.STRING`). Antes de
  volver se comprueba
  `SELECT count(*) FROM simulated_payment_attempts WHERE instrument = 'WOMPI'` = 0. Si hay intentos,
  se corrige hacia adelante: se apaga Wompi quitando las llaves y se despliega una corrección.
  Los intentos son inmutables y no se reescriben.
- No hay que revertir migraciones: la tabla y el CHECK ampliado no afectan a la versión anterior.

## Cobertura de RNF

| RNF | Cómo se cubre | Evidencia |
|-----|---------------|-----------|
| RNF-05 Integridad e idempotencia | Monto firmado, transacción releída en Wompi, referencia/monto/moneda validados, bloqueo del pago, llave de operación y `gateway_transaction_id` únicos, RLS | `PostgresWompiPaymentIT`: concurrencia, duplicados, montos y referencias ajenos, matriz RLS, cobro doble |
| RNF-15 Compatibilidad de contrato | Endpoints nuevos y aditivos; el resto del contrato no cambia; Wompi apagado no altera la pasarela simulada | `OpenApiContractTest`, `PostgresWompiDisabledIT` |
| RNF-16 Errores de sistemas externos | Timeouts cortos, 503 sin cambiar el pago, reintento del webhook, PENDING que se vuelve a consultar, credenciales rechazadas, revisión y compensación documentadas | `PostgresWompiPaymentIT` (Wompi caído y lento), `WompiPaymentGatewayTest` |

## Endpoints

| Método y ruta | Quién | Para qué |
|---------------|-------|----------|
| `GET /payments/wompi` | Usuario autenticado | `{enabled, environment}` |
| `POST /payments/{paymentId}/wompi/checkout` | Propietario o administrador inmobiliario vigente | Crea la referencia y devuelve `checkoutUrl` |
| `POST /payments/{paymentId}/wompi/transactions/{transactionId}` | Propietario o administrador inmobiliario vigente | Verifica con Wompi y aplica el resultado |
| `POST /payments/wompi/events` | Wompi (público, firmado) | Webhook `transaction.updated` |
| `GET /payments/wompi/transactions` | Administración | Transacciones del comercio en Wompi cruzadas con los pagos de FixUp |

`returnUrl` debe pertenecer a un origen de `CORS_ALLOWED_ORIGINS` o `WOMPI_RETURN_ORIGINS` y abrir
`/payments/{paymentId}`.

El checkout de Wompi (CloudFront) responde 403 si `redirect-url` apunta a `localhost` o a una IP, así
que el backend rechaza esos retornos con `WOMPI_RETURN_URL_LOCAL`. En local se abre FixUp en
`http://fixup.localhost:<puerto>`: el navegador lo resuelve a la misma máquina y Wompi lo acepta.

## Ver las transacciones (administración)

En el menú de Administración, **Supervisión → Transacciones Wompi** (`/administration/wompi-transactions`)
lista en vivo las transacciones del comercio en el sandbox con la llave privada de prueba
(`GET /v1/transactions` de Wompi). Filtra por fechas (por omisión los últimos 30 días, máximo
366) y por estado (por omisión las aprobadas). Los datos del comprador no salen de Wompi. Cada
transacción dice cómo quedó en FixUp:

| Estado en FixUp | Qué significa |
|-----------------|---------------|
| Registrada | FixUp registró ese resultado en el pago |
| Sin registrar | Wompi la resolvió, pero FixUp no la registró: no llegó ni el retorno ni el webhook, o quien abrió el checkout ya no era el pagador vigente |
| En espera | Wompi todavía no la resuelve |
| Cobro doble | Aprobada para un pago que ya estaba aprobado por otra transacción: reembolsarla en Wompi |
| Monto distinto | La referencia es de FixUp, pero el monto o la moneda no coinciden con el checkout |
| Ajena a FixUp | La referencia no salió de un checkout de FixUp (otra tienda o pruebas en el comercio) |

## Revisión y compensación de transacciones (sandbox)

Todo esto se hace con el comercio de pruebas; no hay dinero real. Una vez por jornada de pruebas, y
siempre después de una caída de Wompi o del backend, la administración abre **Transacciones Wompi**
con estado "Todas" y el rango de fechas afectado, y resuelve lo que no esté "Registrada":

| Estado | Revisión | Compensación |
|--------|----------|--------------|
| Sin registrar (aprobada en Wompi) | Abrir el pago en FixUp y confirmar quién es el pagador vigente | El pagador vigente abre `/payments/{id}?id=<transacción>` (el enlace de retorno): FixUp vuelve a consultar a Wompi y registra el pago una sola vez |
| Sin registrar (rechazada o con error) | Ninguna: el pago sigue pendiente | El pagador abre un checkout nuevo |
| En espera | Volver a mirar más tarde; Wompi la resuelve y avisa por el webhook | Ninguna |
| Cobro doble | Confirmar que el pago ya estaba aprobado por otra transacción | Anular o reembolsar la segunda transacción desde el panel de Wompi (sandbox). FixUp no crea un segundo ingreso |
| Monto distinto | Comparar el monto y la moneda con el checkout; no debería ocurrir con la firma de integridad | Anular o reembolsar en el panel de Wompi y abrir un checkout nuevo |
| Ajena a FixUp | Otra tienda o pruebas manuales en el mismo comercio | Ninguna |

Cada caso deja rastro en el log del backend ("Evento de Wompi no aplicado…", "Doble cobro en Wompi…")
con el id de la transacción, sin datos del comprador ni llaves.

## Secretos

- Las llaves solo viven en variables de entorno o en `.env.wompi` (permisos 600); `.gitignore` excluye
  `.env` y `.env.*`. En el repositorio no hay llaves: las de las pruebas son valores inventados
  (`pub_test_it`…) y el secreto `prod_integrity_Z5mM…` de `WompiPaymentGatewayTest` es el ejemplo
  público de la documentación de Wompi, usado para comprobar la firma.
- Los logs nombran la regla que falló o el id de la transacción; nunca una llave, un secreto, la firma
  ni el cuerpo de una respuesta de Wompi.

## Probar en sandbox

- Tarjeta aprobada `4242 4242 4242 4242`; rechazada `4111 1111 1111 1111` (fecha futura, CVC de 3 dígitos).
- Nequi aprobado `3991111111`; rechazado `3992222222`.
- PSE: elegir el banco de prueba que aprueba o el que rechaza.

URL de eventos para el panel de Wompi (ambiente de pruebas):
`https://fix-rent.tailb7a45b.ts.net/api/payments/wompi/events`. Sin webhook también funciona, porque
al volver del checkout el backend consulta la transacción.

## Pruebas automáticas

- `WompiPaymentGatewayTest`: firma de integridad contra el ejemplo oficial de Wompi, checksum de
  eventos, consulta con llave privada y Wompi caído.
- `PostgresWompiDisabledIT`: llaves y API de producción rechazadas de punta a punta; la pasarela
  simulada sigue aprobando.
- `PostgresWompiPaymentIT` corre el backend con privilegios equivalentes al runtime de Green: las
  migraciones las aplica otro usuario y el de ejecución (`fixup_runtime`) no es superusuario, no tiene
  `BYPASSRLS` ni DDL, no es dueño de las tablas y solo es miembro de `fixup_app`. Con ese usuario se
  prueba el webhook sin retorno del navegador (pago aprobado, un intento, un ingreso, un trabajo),
  el evento duplicado, el retorno tardío, PENDING, evento sin firma, con otra llave o alterado,
  referencia ajena, monto y moneda distintos, delegación revocada, rol retirado, sesión vencida al
  volver, evento que llega antes de que la API devuelva la transacción (503 y reintento) y que una
  sesión de usuario no puede usar la función de resolución.
- El mismo `PostgresWompiPaymentIT` (PostgreSQL 17 con RLS y triggers, Wompi falso local): aprobación por
  retorno y por webhook sin duplicados, rechazo y reintento con referencia nueva, transacción
  pendiente, firma inválida, monto o referencia ajenos, origen de retorno no permitido y pagadores
  sin permiso, la vista de administración (registrada, sin registrar, pendiente, ajena, monto
  distinto, cobro doble y permisos), la matriz RLS de `wompi_checkouts` (propietario, administrador
  inmobiliario vigente y revocado, tercero, inquilino, técnico y administración), el webhook con la
  delegación revocada, retornos y webhooks simultáneos, Wompi caído (500) y lento (timeout), y la
  pasarela simulada con Wompi habilitado.
- `WompiPaymentGatewayTest`: además, eventos manipulados (otro id, otro monto, sin ambiente, otro
  tipo, sin timestamp) y firma que no cubre `transaction.id`.
