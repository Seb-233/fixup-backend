# SAF-01: conciliación del libro de pagos

La conciliación revisa, con la aplicación en marcha, que la plata registrada en las tablas de pagos
se conserve entre tablas. Encendida, corre cada 10 minutos y cuando un `PLATFORM_ADMIN` la pide con
`GET /administration/payments/reconciliation`. Solo lee: nunca corrige nada.

**Viene apagada** (`fixup.payments.reconciliation.enabled=false`, variable
`FIXUP_LEDGER_RECONCILIATION_ENABLED`). Se enciende por ambiente solo después de aprovisionar el
lector; ver [Runbook](#runbook-encender-la-conciliación-en-un-ambiente).

## Alcance: detecta, no garantiza

SAF-01 **detecta** discrepancias en el libro de pagos; **no garantiza por sí sola que la plataforma
no pierda plata**. En concreto:

- No impide ni corrige un descuadre: lo reporta después de que ocurre, como máximo un intervalo
  (10 min) más tarde, y solo si la conciliación está encendida y puede leer el libro.
- Solo ve lo que está en las tablas del libro. Plata que nunca se registró (por ejemplo, un cobro
  hecho fuera de la plataforma) o un error que deja todas las tablas de acuerdo entre sí no produce
  hallazgos.
- Las barreras que evitan las pérdidas son otras y van antes: triggers de inmutabilidad y
  transiciones (V40–V42), RLS por actor, idempotencia de pagos y transferencias, y la validación
  de eventos de Wompi (INT-01). SAF-01 es la red de detección que comprueba que esas barreras
  sostuvieron, y su valor depende de que alguien atienda las alertas.
- Un `200` con `consistent: true` dice que las reglas y ecuaciones conocidas se cumplen sobre las
  filas leídas (`scanned`) en ese instante; no certifica nada fuera de esas reglas.

## Qué responde

| Respuesta | Significado |
|---|---|
| `200`, `consistent: true` | El libro se leyó completo, ninguna regla tiene filas y todas las ecuaciones cuadran. |
| `200`, `consistent: false` | El libro se leyó y hay diferencias: `findings` (regla, conteo, hasta 20 ids) y `balances` (ecuaciones con `holds: false`). |
| `503 LEDGER_RECONCILIATION_DISABLED` | La conciliación está apagada en este ambiente (configuración por defecto). No toca la base ni las métricas. Sin `Retry-After`: reintentar no cambia nada. |
| `503 LEDGER_UNREADABLE` | Encendida, pero no se pudo leer el libro (rol sin aprovisionar o revocado, permiso de columna, tiempo agotado, base caída). No hay resultado. Trae `Retry-After: 10`. |
| `503 LEDGER_RECONCILIATION_BUSY` | Otra instancia está conciliando. Reintentar; trae `Retry-After: 10`. |
| `401` / `403` | Sin token / sin `PLATFORM_ADMIN` activo. La autorización se revisa antes que el estado: quien no es administrador no sabe si está encendida. |

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
`SET LOCAL ROLE fixup_ledger_auditor` (migración V48).

- Sin login, sin herencia, sin `BYPASSRLS`. RLS le aplica: lo que ve lo decide una política
  `ledger_audit_read` por tabla del libro.
- Solo `SELECT`, y solo sobre las columnas que usan las reglas (montos, estados, identificadores).
  No lee `users`, solicitudes, propiedades ni referencias de pago.
- `fixup_app` no es miembro. La conexión de la aplicación puede asumirlo, pero no hereda sus lecturas.
- Si no puede asumir el rol o le falta un permiso, la corrida falla y responde 503.

Si una regla nueva necesita otra columna, hay que concederla en una migración.

### Runbook: encender la conciliación en un ambiente

No ejecutar estos pasos contra Blue/Green (ni otra base compartida) sin aprobación expresa del
responsable del ambiente. Ningún paso concede privilegios a `fixup_app` ni cambia credenciales.

1. **Migrar.** Desplegar con la conciliación apagada (por defecto). Flyway aplica V48 como el
   usuario migrador. Nada cambia en el comportamiento: el endpoint responde
   `503 LEDGER_RECONCILIATION_DISABLED` y no hay corrida programada ni métricas.
2. **Verificar la identidad del runtime** (como administrador, en la base y esquema del ambiente).
   Debe salir una fila con todo en `false` y `owned_relations = 0`:

   ```sql
   SELECT r.rolname, r.rolsuper, r.rolbypassrls, r.rolcreatedb, r.rolcreaterole, r.rolreplication,
          (SELECT COUNT(*) FROM pg_class c WHERE c.relowner = r.oid) AS owned_relations,
          has_schema_privilege(r.oid, current_schema(), 'CREATE') AS schema_create
   FROM pg_roles r WHERE r.rolname = 'runtime_login';
   ```

   Si el runtime es superusuario, dueño de las tablas o tiene `BYPASSRLS`, detenerse: el script lo
   rechaza y el ambiente no cumple RNF-11/12 de todos modos.
3. **Aislamiento entre ambientes.** Los roles de PostgreSQL son de todo el clúster, no de una base.
   Si Blue y Green comparten clúster, la membresía en `fixup_ledger_auditor` de un login sirve en
   cualquier base del clúster donde V48 haya concedido lecturas a ese rol. Comprobar que el login
   de un ambiente no puede conectarse a la base del otro:

   ```sql
   SELECT datname, has_database_privilege('runtime_login', datname, 'CONNECT')
   FROM pg_database WHERE NOT datistemplate;
   ```

   Solo debe salir `true` en la base de su ambiente; si no, `REVOKE CONNECT ... FROM PUBLIC` y
   conceder `CONNECT` solo a su login, o usar clústeres separados.
4. **Aprovisionar** con `scripts/provision-ledger-reader.sql` (detalle abajo).
5. **Preflight manual** como el runtime, antes de encender. Debe devolver `fixup_ledger_auditor` y
   no dar error:

   ```sql
   BEGIN READ ONLY;
   SET LOCAL ROLE fixup_ledger_auditor;
   SELECT current_user;
   SELECT COUNT(*) FROM repair_payments;
   ROLLBACK;
   ```
6. **Encender** con `FIXUP_LEDGER_RECONCILIATION_ENABLED=true` y desplegar. Al arrancar, la
   aplicación hace el mismo preflight (`ReconcileLedger.preflight`): asume el rol y planea con
   `LIMIT 0` todas las consultas de las reglas, sin leer filas ni tomar el turno entre réplicas.
   **Si falla, la aplicación no arranca**: en Blue/Green la versión nueva no pasa a recibir tráfico
   y la anterior sigue sirviendo. El error nombra este script.
7. **Comprobar**: `GET /administration/payments/reconciliation` como `PLATFORM_ADMIN` responde
   `200` con `scanned` distinto de cero en un ambiente con datos, y
   `fixup.payments.ledger.unreadable` queda en 0.

**Volver atrás:** `FIXUP_LEDGER_RECONCILIATION_ENABLED=false` y redesplegar. Para retirar el acceso:
`REVOKE fixup_ledger_auditor FROM runtime_login;`. V48 puede quedarse: el rol no tiene login ni
miembros.

### Aprovisionar un runtime distinto de Flyway

V48 crea el lector y sus permisos de lectura, sin conceder membresía al migrador ni al runtime.
Es obligatorio ejecutar **después de migrar y antes de habilitar la conciliación** el script
administrativo `scripts/provision-ledger-reader.sql`. Es un paso de aprovisionamiento, no una
operación del backend. No se cambian Kubernetes, `fixup_app` ni las credenciales de la aplicación.

Ejemplo con un servicio libpq de administración ya configurado (sin contraseñas en la línea):

```sh
psql 'service=fixup-migrations' -X --set=ON_ERROR_STOP=1 \
  --command="SET fixup.ledger_runtime_role = 'runtime_login';" \
  --file=scripts/provision-ledger-reader.sql
```

Sustituir `runtime_login` por el login autorizado de ese entorno y usar el esquema de la aplicación
en `search_path`. El script valida que sea un login diferente al administrador, sin superusuario,
`BYPASSRLS`, creación de roles/bases, replicación, propiedad de objetos ni `CREATE` en el esquema.
Rechaza una configuración insegura del lector o su acceso desde `fixup_app`. Solo concede
`fixup_ledger_auditor` con `ADMIN FALSE, INHERIT FALSE, SET TRUE`; no otorga privilegios de tablas.
Es atómico e idempotente. Sin este paso, una lectura imposible devuelve 503 y el despliegue no está
preparado. No se debe ejecutar contra Blue/Green o bases compartidas durante las pruebas locales.

`PostgresLedgerRestrictedRuntimeIT` ejecuta el mismo archivo operativo: Flyway crea el auditor
como administrador, el runtime arranca sin su membresía, y el script la concede después. La prueba
comprueba en una instalación limpia que V48 no concede ninguna membresía y que el runtime no
puede asumir el lector hasta ejecutar el script. También
revoca y repone la concesión, comprueba que no hay herencia ni opción de administración y que
`SET LOCAL ROLE` se revierte al terminar la transacción.

## Límites de operación

| Límite | Valor | Propiedad |
|---|---|---|
| Frecuencia programada | 10 min (primera a 1 min del arranque) | `fixup.payments.reconciliation.interval`, `.initial-delay` |
| Tiempo máximo por corrida | 30 s (transacción y `statement_timeout`) | `fixup.payments.reconciliation.timeout` |
| Corridas simultáneas por instancia | 1; las peticiones que llegan durante una corrida reciben ese resultado | |
| Corridas simultáneas entre réplicas | 1; turno con `pg_try_advisory_xact_lock` | |
| Encender (corrida programada, consulta manual, métricas y preflight de arranque) | apagada por defecto | `fixup.payments.reconciliation.enabled` (`FIXUP_LEDGER_RECONCILIATION_ENABLED`) |

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
Apagada, no se registra ningún medidor: un `violations = 0` sin corridas se leería como "cuadra".

### Qué no sale en logs ni métricas

- Métricas: solo los tres nombres `fixup.payments.ledger.*` de arriba, con una única etiqueta
  (`outcome`) de valores fijos. Sin importes, identificadores, usuarios ni etiquetas de alta
  cardinalidad.
- Log `INFO`/`WARN`: solo "sin diferencias" o los códigos de las ecuaciones que no cuadran y
  `REGLA=conteo`. Sin importes ni identificadores de fila: esos solo están en la respuesta, que solo
  ve un `PLATFORM_ADMIN`.
- Log `ERROR` (`LEDGER_UNREADABLE`): el mensaje de PostgreSQL y el texto de la consulta fija que
  falló (permiso denegado, tiempo agotado). Las consultas no llevan valores de filas.
- `ReconcileLedgerTest.logsAndMetersCarryNoAmountsNorRowIdentifiers` lo comprueba.

## Pruebas

La versión del lector es **V48**, la siguiente a la última de `develop` (V45/V46/V47 de Wompi,
#54). Auditoría (#57) ya usa V49/V50, así que entra después sin versiones menores. Flyway corre con
`outOfOrder` desactivado: una versión menor que la última aplicada hace fallar `validate` y el
arranque. Antes de instalar en una base existente hay que comprobar que ninguna V47 de conciliación
se haya aplicado (versiones viejas de esta rama): si existe, detener la actualización y revisar ese
historial; no renombrar una migración aplicada ni activar `outOfOrder`.

El contrato prueba ingresos sin pago un segundo antes de `flyway_schema_history.installed_on`
de V41, exactamente en el corte y un segundo después. El primero sigue sumando como histórico;
los otros dos generan `EARNING_WITHOUT_APPROVED_PAYMENT`. La comisión de un ingreso histórico
también se valida: la excepción histórica no excluye sus importes del libro.

- `LedgerReconciliationContextTest` (H2) y `PostgresLedgerReconciliationIT` (encendida): libro
  representativo sin falsos positivos, un descuadre real por cada una de las nueve reglas,
  autorización 401/403/200 y administrador suspendido, foto coherente con pagos entrando, turno
  entre réplicas (otra conexión con el mismo advisory lock → `503 BUSY`), libro ilegible por
  permiso de columna (`503 UNREADABLE`) y corrida cortada por `statement_timeout` con otra
  transacción bloqueando la tabla: sin resultado en menos de 7 s, la transacción abortada no deja la
  conexión con el rol lector y la corrida siguiente cuadra.
- `LedgerReconciliationDisabledContextTest` (configuración por defecto): `401`, `403` y
  `503 LEDGER_RECONCILIATION_DISABLED` sin `Retry-After` ni totales.
- `PostgresLedgerRestrictedRuntimeIT`: el runbook en orden (Flyway como administrador, V48 sin
  membresías, el runtime no puede asumir el lector, script, preflight de arranque). La aplicación
  conectada como un rol sin superusuario, `BYPASSRLS`, propiedad de tablas ni `CREATE`, con filas de
  varios propietarios; el runtime es el único miembro del lector (`SET` sí, `INHERIT`/`ADMIN` no);
  al revocarle el lector en caliente responde `503 UNREADABLE` y el preflight falla.
- `PostgresDigitalPaymentMigrationIT`: upgrade real con datos de V38 → V40 → V47 (develop) → V48;
  la única pendiente sobre V47 es V48 y `validate` pasa en cada paso.
- `ReconcileLedgerTest`: límites de operación sin base de datos, apagada, preflight y contenido de
  logs y métricas.
