# Validación FR-UC-20 — monitoreo de ingresos del técnico

Fecha local: 2026-09-19 (America/Bogota). Repositorio: Seb-233/fixup-backend.
Rama: `feature/fr-uc-20-fixer-earnings`, sobre `develop`.

> **Base actualizada.** FR-UC-20 cuelga del evento `QuotationAccepted` y del módulo
> `quotations`, que entraron a `develop` con el PR #28. La rama se rebasó sobre esa base, así
> que su diff son solo los commits del caso. La migración quedó como `V6`: `V3` y `V4` se
> las llevaron el portafolio y los indicadores de mercado, y `V5` el caso 18.

> **Estado de la verificación: ejecutada.** `clean verify` corrió en el CI sobre esta rama y
> quedó en verde. Las casillas de abajo se llenaron con esa corrida, no a mano.

## Alcance

Dos módulos que hasta ahora solo tenían la estructura de paquetes.

`jobs` recibe el corte mínimo que FR-UC-20 necesita: el trabajo nace al aceptarse una cotización
y el técnico lo cierra. **No incluye los estados intermedios** —en camino, en ejecución— ni las
fotos de evidencia: eso es FR-UC-19 y se agregará con ese caso. Aquí el trabajo existe solo
porque su cierre es el momento en que el dinero se libera.

`payments` recibe el caso completo: ingresos retenidos y liberados, comisiones, saldo disponible
y solicitud de transferencia.

## Decisiones

- **Escrow en dos momentos.** Aceptar la cotización no entrega el dinero, lo compromete: el
  ingreso nace `HELD`. Cerrar el trabajo lo pasa a `AVAILABLE`. Transferirlo lo deja `PAID_OUT`.
  Esto es lo que el spreadsheet declara como ASR del caso.
- **Comisión del 10% en puntos básicos, con aritmética entera** sobre pesos enteros. No hay coma
  flotante que arrastre un centavo de diferencia. La división trunca hacia abajo, así que un
  residuo indivisible queda del lado del técnico y nunca de la plataforma.
- **La tarifa se copia en cada ingreso** al crearlo. Cambiarla mañana no puede reescribir lo que
  ya se liquidó.
- **Los tres montos se guardan** —bruto, comisión y neto— en vez de recalcular dos a partir del
  tercero, y una restricción de la base exige que sumen. La plata cuadra en el esquema, no solo
  en el código.
- **La comisión se muestra como número propio** en el panel, no como la diferencia entre dos
  cifras que el técnico tendría que restar.
- **La transferencia es el caso ACID.** Leer el saldo, registrar la solicitud y consumir los
  ingresos ocurre en una sola transacción, y las filas se bloquean antes de sumar: dos
  solicitudes simultáneas no pueden llevarse el mismo dinero. No se registran transferencias
  de saldo cero.
- **El cliente nunca dice cuánto transferir.** Se transfiere el saldo disponible completo, que el
  backend calcula. La operación además **rechaza con 400 cualquier cuerpo**: aceptarlo en silencio
  dejaría a quien llama creyendo que decidió un monto que nunca se leyó.
- **El historial muestra los tres momentos del escrow.** Retenido, liberado y transferido, cada
  uno con su fecha. Guardar `paidOutAt` y no exponerlo obligaba al técnico a deducir cuándo le
  transfirieron.
- **Sin ciclos entre módulos.** `payments` depende de `jobs.api` y `quotations.api`; `jobs`
  depende de `quotations.api` y `fixers.api`. Nadie depende de `payments`.
- **Cerrar un trabajo exige Fixer verificado**, invocando `FixerEligibility` como pide
  `module-rules.md`.

## Contrato expuesto

| Método | Ruta | Éxito |
| --- | --- | --- |
| GET | `/jobs/me` | 200 |
| POST | `/jobs/{jobId}/complete` | 200 |
| GET | `/payments/me/earnings` | 200 |
| POST | `/payments/me/payouts` | 201 |
| GET | `/payments/me/payouts` | 200 |

## Verificación automatizada

| Ejecución | Resultado |
| --- | --- |
| `clean verify` en el CI (Backend CI #58, Linux) | verde, 291 pruebas |
| Arquitectura (ArchUnit y Spring Modulith, 14 módulos) | verde |
| OpenAPI (rutas, respuestas y enums) | verde |

`docs/openapi.json` quedó regenerado con las cinco operaciones, que es de donde el frontend
genera su cliente tipado.

> **Intermitencia ajena, anotada para no perderla.** En Windows, `MediaDeletionAfterCommitTest`
> —del módulo `media`— falla cerca de la mitad de las veces sobre esta rama, y nunca en el CI;
> sobre `develop` pasa siempre en ambos. Esta rama no toca `media`. Al instrumentar la prueba, el
> trabajo de borrado queda en `PENDING` con cero intentos y sin error, y sigue igual 500 ms
> después: el listener de `AFTER_COMMIT` no se dispara. Con el diagnóstico puesto pasa siempre,
> lo que apunta a una carrera sensible al tiempo de arranque y no a un error de lógica. Queda
> reportado en el PR para que se atienda desde ese módulo.

## Pruebas agregadas

- `JobTest`: el trabajo se cierra una sola vez y solo lo cierra el técnico asignado.
- `FixerEarningTest`: la comisión del 10%, el residuo indivisible que queda del lado del técnico,
  que bruto = comisión + neto para varios montos, el ciclo completo del escrow, y que un ingreso
  descuadrado no se puede construir siquiera.
- `OpenApiContractTest`: cinco rutas nuevas fijadas junto a las de `media` y `analytics` que ya
  estaban, los enums del ciclo del dinero, y que la transferencia no acepta cuerpo.
- `IntegrationDatabaseCleaner`: las tres tablas del escrow se borran antes que `quotations`,
  que es a quien apuntan.
- `EarningsHttpContract`: el recorrido completo por HTTP contra la base —aceptar la cotización
  retiene el dinero, cerrar el trabajo lo libera, la transferencia se lleva el saldo entero—,
  que un tercero no cierra el trabajo ajeno ni libera esa plata, que cerrar dos veces no paga
  dos veces, que un monto en el cuerpo de la transferencia no cambia lo que se transfiere, que
  el propietario no ve trabajos ni saldo, y que dos transferencias simultáneas no se llevan el
  mismo dinero. Corre contra H2 en `EarningsContextTest` y contra PostgreSQL real en
  `PostgresEarningsIT`, que es donde el bloqueo de filas dice algo.

## Límites de la evidencia

La liquidación efectiva hacia una cuenta
bancaria no existe: el alcance académico llega a registrar la solicitud, igual que FR-UC-22
declara para los pagos del propietario.
