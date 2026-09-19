# Validación FR-UC-20 — monitoreo de ingresos del técnico

Fecha local: 2026-09-19 (America/Bogota). Repositorio: Seb-233/fixup-backend.
Rama: `feature/fr-uc-20-fixer-earnings`, apilada sobre `feature/fr-uc-18-quotations`.

> **Rama apilada.** FR-UC-20 cuelga del evento `QuotationAccepted` y del módulo `quotations`,
> que viven en el PR #28 y todavía no están en `develop`. Mientras ese PR no se integre, el
> diff de este incluye también los commits de FR-UC-18. Se limpia solo cuando #28 entre.

> **Estado de la verificación: pendiente.** El entorno donde se redactó este cambio no tiene
> acceso a Maven Central, así que `clean verify` no se ejecutó aquí. Nadie debe marcar estas
> casillas sin haber corrido las órdenes.

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
  backend calcula. Un monto en el cuerpo sería un monto que el cliente podría inflar.
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
| `.\mvnw.cmd clean verify` | pendiente |
| Arquitectura (ArchUnit y Spring Modulith, 14 módulos) | pendiente |
| OpenAPI (rutas, respuestas y enums) | pendiente |

`docs/openapi.json` **no** se regeneró: se produce desde `target/openapi.json` durante las
pruebas. Debe regenerarse antes de abrir el PR, o el frontend no puede generarse contra este
contrato.

## Pruebas agregadas

- `JobTest`: el trabajo se cierra una sola vez y solo lo cierra el técnico asignado.
- `FixerEarningTest`: la comisión del 10%, el residuo indivisible que queda del lado del técnico,
  que bruto = comisión + neto para varios montos, el ciclo completo del escrow, y que un ingreso
  descuadrado no se puede construir siquiera.
- `OpenApiContractTest`: cinco rutas nuevas fijadas, los enums del ciclo del dinero, y que la
  transferencia no acepta cuerpo.

Falta el contrato HTTP de extremo a extremo: aceptar cotización, cerrar trabajo, consultar saldo
y transferir, todo por HTTP contra la base.

## Límites de la evidencia

Nada de lo escrito aquí se ejecutó todavía. Además, la liquidación efectiva hacia una cuenta
bancaria no existe: el alcance académico llega a registrar la solicitud, igual que FR-UC-22
declara para los pagos del propietario.
