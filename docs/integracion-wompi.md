# INT-01: pasarela Wompi

FixUp cobra el servicio de reparación y las deudas de propietarios con **Wompi** (Colombia) en
ambiente sandbox. La pasarela simulada (FR-UC-22) sigue disponible; Wompi aparece en la pantalla
de pago solo cuando el backend tiene las cuatro llaves.

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
lista en vivo las transacciones del comercio con la llave privada (`GET /v1/transactions` de Wompi),
del ambiente de las llaves configuradas. Filtra por fechas (por omisión los últimos 30 días, máximo
366) y por estado (por omisión las aprobadas). Los datos del comprador no salen de Wompi. Cada
transacción dice cómo quedó en FixUp:

| Estado en FixUp | Qué significa |
|-----------------|---------------|
| Registrada | FixUp registró ese resultado en el pago |
| Sin registrar | Wompi la resolvió, pero el pagador no volvió del checkout y no llegó el webhook |
| En espera | Wompi todavía no la resuelve |
| Cobro doble | Aprobada para un pago que ya estaba aprobado por otra transacción: reembolsarla en Wompi |
| Monto distinto | La referencia es de FixUp, pero el monto o la moneda no coinciden con el checkout |
| Ajena a FixUp | La referencia no salió de un checkout de FixUp (otra tienda o pruebas en el comercio) |

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
- `PostgresWompiPaymentIT` (PostgreSQL 17 con RLS y triggers, Wompi falso local): aprobación por
  retorno y por webhook sin duplicados, rechazo y reintento con referencia nueva, transacción
  pendiente, firma inválida, monto o referencia ajenos, origen de retorno no permitido y pagadores
  sin permiso, y la vista de administración (registrada, sin registrar, pendiente, ajena, monto
  distinto, cobro doble y permisos).
