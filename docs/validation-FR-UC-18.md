# Validación FR-UC-18 — responder solicitudes y cotizar

Fecha local: 2026-09-18 (America/Bogota). Repositorio: Seb-233/fixup-backend.
Rama: `feature/fr-uc-18-quotations`.
Base: `develop`, commit `78fb6a5`.

Base: `develop`, commit `78fb6a5`. Verificación ejecutada el 2026-09-18 sobre
Microsoft OpenJDK 21.0.12 en Windows.

## Alcance

Corte vertical de FR-UC-18 sobre dos módulos que hasta ahora solo tenían la estructura
de paquetes: `requests` y `quotations`.

- `requests` recibe el corte mínimo que el caso necesita: abrir una solicitud, listarla
  para su dueño, ofrecerla a los Fixers y leer su detalle. No incluye cancelación ni
  edición; la solicitud solo sale de OPEN cuando una cotización es aceptada.
- `quotations` recibe el caso completo: enviar la oferta, listarla, compararla y aceptarla.

## Decisiones

- Cotizar exige Fixer verificado. `SubmitQuotation` invoca `FixerEligibility.requireVerified`,
  que es la regla que `module-rules.md` exige antes de dejar trabajar a un técnico. Leer la
  bandeja de solicitudes abiertas no lo exige: un técnico en revisión puede mirar el mercado
  aunque todavía no pueda ofertar.
- La comunicación entre módulos va en una sola dirección. `quotations` depende de
  `requests.api` a través de `RepairRequestDirectory` para leer la solicitud y cerrarla;
  `requests` no conoce `quotations`. No hay ciclo que Spring Modulith pueda reprochar.
- `QuotationAccepted` se publica desde `quotations.api` para que FR-UC-20 cuelgue de ese
  hecho sin invertir la dependencia. El monto viaja bruto: la comisión es una regla de
  `payments`, no de `quotations`.
- Aceptar es una sola transacción: la cotización elegida pasa a ACCEPTED, las demás a
  REJECTED y la solicitud a ASSIGNED. `RepairRequestDirectory.assign` usa
  `Propagation.MANDATORY` justamente para que no pueda confirmarse por separado.
- Las filas se bloquean antes de decidir, igual que en la revisión del Fixer: dos
  aceptaciones concurrentes sobre la misma solicitud no pueden ganar ambas.
- Una oferta por Fixer por solicitud, garantizada por índice único además de por la regla
  de aplicación. Un segundo intento es un conflicto, no una fila duplicada.
- El monto se guarda en pesos colombianos enteros y el plazo en días enteros. No hay
  centavos que redondear ni coma decimal que interpretar entre el front y el back.
- La autorización se resuelve sobre el registro y no sobre la pantalla (FR-UC-25). El dueño
  lee su solicitud; un Fixer la lee mientras está en oferta porque necesita la descripción y
  las fotos para cotizar, y después solo si el trabajo quedó asignado a él. Un identificador
  adivinado no abre la solicitud ni las cotizaciones de otro.
- Las fotografías viajan como storage keys, igual que los documentos de verificación. Ningún
  contenido de imagen cruza esta API.
- El autor de una cotización sale del token validado, nunca del cuerpo que manda el cliente.

## Contrato expuesto

| Método | Ruta | Éxito |
| --- | --- | --- |
| POST | `/requests` | 201 |
| GET | `/requests/me` | 200 |
| GET | `/requests/open` | 200 |
| GET | `/requests/{requestId}` | 200 |
| POST | `/quotations` | 201 |
| GET | `/quotations/me` | 200 |
| GET | `/quotations/for-request/{requestId}` | 200 |
| POST | `/quotations/{quotationId}/accept` | 200 |

Solo GET y POST: el CORS del proyecto no habilita otros métodos.

## Verificación automatizada

| Ejecución | Resultado |
| --- | --- |
| `.\mvnw.cmd clean verify` | BUILD SUCCESS; 110 pruebas, 0 fallos, 0 errores, 0 omitidas |
| Arquitectura (ArchUnit y Spring Modulith, 14 módulos) | aprobado |
| OpenAPI (rutas, respuestas, enums y campos opcionales) | aprobado |
| `-Ppostgres-it verify` contra PostgreSQL real | no ejecutado |
| Docker y pruebas HTTP reales | no ejecutado |

La primera ejecución falló entera: `photo_order` se declaró `SMALLINT` en la migración
mientras Hibernate mapea `@OrderColumn` a `integer`, así que la validación de esquema
tumbó el `EntityManagerFactory` y ningún contexto de Spring arrancó. Los 79 errores eran
el mismo fallo en cascada. Se corrigió la columna y se repitió el `clean verify` completo;
el resultado aprobado de arriba es posterior a esa corrección.

`docs/openapi.json` se regeneró desde `target/openapi.json`. La superficie expuesta pasó
de siete rutas a quince.

## Pruebas agregadas

- `RepairRequestTest`: ciclo de vida de la solicitud, límite de fotos, y las reglas de
  visibilidad del dueño, del Fixer mientras está en oferta y del Fixer asignado.
- `QuotationTest`: máquina de estados de la oferta, monto positivo y plazo dentro de rango.
- `OpenApiContractTest`: se amplió la lista fijada de rutas y se agregaron aserciones sobre
  los enums nuevos y sobre los campos que el cliente no debe poder mandar.

Falta el contrato HTTP de extremo a extremo al estilo de `FixerVerificationHttpContract`,
que debe agregarse cuando el caso se pruebe contra la base de datos.

## Límites de la evidencia

Las pruebas corrieron con H2 en modo PostgreSQL. Este caso **no** se repitió contra
PostgreSQL real con el perfil `postgres-it`, ni se levantó el contenedor, ni se hicieron
llamadas HTTP reales contra el puerto del backend. FR-UC-21 sí tiene esa evidencia; este
caso todavía no.

Falta además el contrato HTTP de extremo a extremo al estilo de
`FixerVerificationHttpContract`: hoy las reglas de dominio están cubiertas y el contrato
OpenAPI está fijado, pero ningún test recorre el flujo completo de abrir una solicitud,
cotizarla y aceptarla a través de HTTP.
