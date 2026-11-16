# SAF-01: conciliación del libro de pagos

La conciliación revisa, con la aplicación en marcha, que la plata registrada en las tablas de pagos
se conserve entre tablas. Corre cada 10 minutos y cuando un `PLATFORM_ADMIN` la pide con
`GET /administration/payments/reconciliation`. Solo lee: nunca corrige nada.

## Qué responde

| Respuesta | Significado |
|---|---|
| `200`, `consistent: true` | El libro se leyó completo, ninguna regla tiene filas y todas las ecuaciones cuadran. |
| `200`, `consistent: false` | El libro se leyó y hay diferencias: `findings` (regla, conteo, hasta 20 ids) y `balances` (ecuaciones con `holds: false`). |
| `503 LEDGER_UNREADABLE` | No se pudo leer el libro (permiso, tiempo agotado, base caída). No hay resultado. |
| `503 LEDGER_RECONCILIATION_BUSY` | Otra instancia está conciliando. Reintentar; trae `Retry-After: 10`. |

"Sin hallazgos" y "no se pudo leer" nunca se confunden: un 200 siempre trae `scanned`, con las filas
leídas de cada tabla. Los importes son pesos colombianos enteros. La respuesta no trae datos
personales: solo sumas, conteos e identificadores de fila.

## Ecuaciones por tipo de operación

Cada peso entra por un solo lado de una sola ecuación de su tipo (`LedgerEquation`).

| Tipo | Ecuación |
|---|---|
| Servicio | cobrado por servicios = bruto de los ingresos de servicio |
| Saldo pendiente | cobrado por saldos = bruto de los ingresos de saldo = deudas pagadas |
| Ingresos | bruto = servicio + saldo + histórico; bruto = comisión + neto; neto = retenido + disponible + transferido |
| Transferencias | transferencias con composición = sus renglones = ingresos transferidos que contienen |
| Efectivo histórico | acordado = recibido en mano + pendiente; pendiente = deudas pagadas + deudas por pagar |

Un pago de saldo pendiente (`OUTSTANDING_BALANCE`) se compara solo con los ingresos y deudas de
saldo, nunca con los ingresos de servicio. Si un mismo ingreso respalda un pago de servicio y uno de
saldo, la ecuación "cada ingreso viene de un solo origen" deja de cuadrar.

## Qué queda por fuera y por qué

| Dato | Tratamiento | Motivo |
|---|---|---|
| Efectivo recibido en mano (`cash_settlements.received_amount`) | No es plata cobrada ni genera ingreso o comisión. Solo se cruza con lo acordado y la deuda. | Nunca pasó por la plataforma. |
| Ingresos creados antes del corte digital (V41) y sin pago aprobado | Fuera de la regla "ingreso sin pago". Se muestran en `legacyEarningsGross`. | Antes del corte un ingreso podía nacer sin pago digital: no hay contra qué cruzarlo. |
| Transferencias sin `operation_key` e ingresos transferidos antes de V41 | Fuera de las reglas y ecuaciones de renglones. Se muestran en `legacyPayouts` y `legacyPaidOutNet`. | V41 no reconstruyó su composición. |
| Pagos pendientes, rechazados o con error | No entran en ninguna suma. | No son plata cobrada. |

El corte histórico es la fecha en que Flyway aplicó V41 en esa base
(`flyway_schema_history.installed_on`). Límite conocido: un ingreso insertado a mano con fecha
anterior al corte no dispara la regla; se nota porque `legacyEarningsGross` cambia, y ese total no
debería moverse nunca.

Las transacciones de Wompi (INT-01) se registran en `simulated_payment_attempts`, igual que las del
sandbox, así que la regla "pago aprobado sin intento aprobado" las cubre sin cambios.

## Lectura de sistema con RLS

Las tablas del libro tienen `FORCE ROW LEVEL SECURITY`. Sin actor, un rol que no sea superusuario no
ve filas, y una conciliación sobre cero filas diría "cuadra". Por eso la conciliación no depende del
usuario con que se conecta la aplicación: dentro de su transacción hace
`SET LOCAL ROLE fixup_ledger_auditor` (migración V47).

- Sin login, sin herencia, sin `BYPASSRLS`. RLS le aplica: lo que ve lo decide una política
  `ledger_audit_read` por tabla del libro.
- Solo `SELECT`, y solo sobre las columnas que usan las reglas (montos, estados, identificadores).
  No lee `users`, solicitudes, propiedades ni referencias de pago.
- `fixup_app` no es miembro. La conexión de la aplicación puede asumirlo, pero no hereda sus lecturas.
- Si no puede asumir el rol o le falta un permiso, la corrida falla y responde 503.

Si una regla nueva necesita otra columna, hay que concederla en una migración.

## Límites de operación

| Límite | Valor | Propiedad |
|---|---|---|
| Frecuencia programada | 10 min (primera a 1 min del arranque) | `fixup.payments.reconciliation.interval`, `.initial-delay` |
| Tiempo máximo por corrida | 30 s (transacción y `statement_timeout`) | `fixup.payments.reconciliation.timeout` |
| Corridas simultáneas por instancia | 1; las peticiones que llegan durante una corrida reciben ese resultado | |
| Corridas simultáneas entre réplicas | 1; turno con `pg_try_advisory_xact_lock` | |
| Apagar la corrida programada | | `fixup.payments.reconciliation.enabled=false` |

La lectura es una transacción de solo lectura `REPEATABLE READ`: no bloquea pagos ni espera por ellos.

## Métricas y alertas

| Métrica | Alertar cuando |
|---|---|
| `fixup.payments.ledger.violations` (gauge) | mayor que 0: filas o ecuaciones que no cuadran |
| `fixup.payments.ledger.unreadable` (gauge) | igual a 1: la última corrida no leyó el libro |
| `fixup.payments.ledger.reconciliation.runs{outcome}` (counter) | sin `consistent` nuevos en 30 min, o crece `unreadable` |

`outcome` es `consistent`, `inconsistent`, `skipped` (otra réplica tenía el turno) o `unreadable`.
Hoy el despliegue solo expone `/actuator/health`; las métricas quedan registradas en Micrometer y el
mismo resultado sale en el log (`Conciliación de pagos ...`, nivel WARN o ERROR si hay problema).

## Pruebas

- `LedgerReconciliationContextTest` (H2) y `PostgresLedgerReconciliationIT`: libro representativo sin
  falsos positivos, un descuadre real por cada una de las nueve reglas, autorización 401/403/200,
  foto coherente con pagos entrando, turno entre réplicas y libro ilegible.
- `PostgresLedgerRestrictedRuntimeIT`: la aplicación conectada como un rol sin superusuario ni
  `BYPASSRLS`, con filas de varios propietarios.
- `ReconcileLedgerTest`: límites de operación sin base de datos.
