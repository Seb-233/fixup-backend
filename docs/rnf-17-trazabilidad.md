# RNF-17 — Trazabilidad de comprobabilidad

> **Requisito.** Los flujos críticos deberán poder verificarse mediante pruebas automatizadas
> repetibles con datos, versión, precondiciones y resultados observables.
>
> **Métrica declarada.** JUnit/Testcontainers con PostgreSQL y Playwright; enlazar guiones, versión,
> resultados, errores y evidencia de CI.
>
> Flujos críticos nombrados: **UC-01, UC-13, UC-16, UC-19**.

Versión trazada: `06d63f0` de `develop`. 119 clases de prueba, 613 métodos `@Test`.

Este documento no es un informe de cobertura. Es el enlace que pide la métrica: para cada flujo
crítico, qué guion lo verifica, con qué datos, bajo qué precondiciones, qué se observa como
resultado y dónde queda la evidencia cuando el CI corre.

## Cómo está construida la comprobabilidad en este repositorio

Antes de la matriz conviene entender el patrón, porque es él —y no cada prueba suelta— lo que
responde a "repetibles con datos y precondiciones".

**Un contrato, dos motores.** Los recorridos por HTTP viven en una clase abstracta `*HttpContract`
que no se ejecuta sola. La heredan dos clases: una `*ContextTest`, que la corre contra H2 en modo
PostgreSQL en cada compilación, y una `Postgres*IT`, que la corre contra un PostgreSQL real
levantado con Testcontainers. El mismo recorrido, las mismas aserciones, dos motores de base de
datos. Nueve flujos están construidos así hoy.

Esto importa para el requisito por una razón concreta: lo que depende del motor —las políticas de
seguridad a nivel de fila, el aislamiento transaccional, los bloqueos— solo se comprueba de verdad
contra PostgreSQL, y lo que no depende del motor se comprueba rápido en cada compilación sin
esperar un contenedor. Una prueba que solo corre contra H2 puede estar verificando una invariante
que en producción la impone la base y no el código.

**Dos velocidades, un comando.** Las clases `*Test` las ejecuta Surefire en `mvn verify`. Las
clases `*IT` solo corren bajo el perfil `postgres-it`, con Failsafe. El CI siempre usa el perfil:

```
./mvnw --batch-mode --no-transfer-progress -Ppostgres-it clean verify
```

De ahí sale una consecuencia que hay que tener presente al leer la matriz: **un flujo verificado
solo por una clase `*IT` no se comprueba en una compilación local normal**, únicamente en el CI o
cuando alguien pide el perfil a mano.

**Precondiciones explícitas y base limpia.** Ninguna prueba depende de datos preexistentes. Cada
recorrido registra sus propios usuarios por la API, elige rol, crea el inmueble y avanza el flujo
con las mismas operaciones que usaría la aplicación. Lo que se escribe por SQL directo es lo que en
el producto ocurre fuera de ese camino: otorgar un rol (`user_roles`), aprobar la especialidad de un
técnico (`fixer_specialties`) o dejar un medio cargado (`media_assets`). Antes de cada prueba,
`testsupport/IntegrationDatabaseCleaner` vacía las tablas en orden inverso de dependencias, así que
el orden de ejecución no cambia el resultado. Eso es lo que vuelve "repetible" una afirmación
verificable y no una esperanza: la precondición es un guion, no un volcado de base que alguien tiene
que acordarse de restaurar.

**Los resultados se observan en dos niveles.** Las pruebas no se conforman con el código y el cuerpo
de la respuesta HTTP: consultan el estado que quedó en la base. Afirmaciones como
`SELECT count(*) FROM jobs WHERE request_id = ? AND status = 'ASSIGNED'` o
`SELECT count(*) FROM fixer_earnings WHERE quotation_id = ? AND status = 'HELD'` aparecen a lo largo
de los contratos. Esto importa para el requisito porque un endpoint puede responder 200 y no haber
escrito lo que debía; el segundo nivel es el que convierte "resultados observables" en algo que
distingue las dos cosas.

## Matriz por flujo crítico

### UC-01 — Proyecto de remodelación multi‑profesional

| | |
| --- | --- |
| **Superficie** | `POST /remodelings`, `GET /remodelings/me`, `GET /remodelings/{id}`, `POST /remodelings/{id}/publish-listing` |
| **Contrato** | `src/test/java/com/fixup/integration/RemodelingHttpContract.java` — 9 pruebas |
| **Sobre H2** | `RemodelingContextTest` (hereda las 9) |
| **Sobre PostgreSQL** | `PostgresRemodelingIT` (hereda las 9 y agrega 6 propias) |
| **Unitarias de dominio** | `remodelings/RemodelingProjectTest` (4), `remodelings/RemodelingProgressTest` (2) |
| **Precondiciones** | Propietario registrado e inmueble creado por la API dentro de la propia prueba; base vaciada antes de cada una |
| **Resultados observables** | Código HTTP, cuerpo JSON del proyecto y sus requisitos, estado de avance, publicación del aviso, y el estado en base de `jobs`, `fixer_earnings` y `cash_settlements` |
| **Estado** | **Trazable.** Tiene dueño nombrado y corre en los dos motores |

### UC-16 — Registro y validación del técnico

| | |
| --- | --- |
| **Superficie** | Envío de documentos, cola de revisión, decisión del administrador, perfil público |
| **Contrato** | `src/test/java/com/fixup/integration/FixerVerificationHttpContract.java` — 33 pruebas |
| **Sobre H2** | `FixerVerificationContextTest` (hereda las 33) |
| **Sobre PostgreSQL** | `PostgresFixerVerificationIT` (hereda las 33 y agrega 1 propia) |
| **Unitarias de dominio** | `fixers/FixerProfileTest` (12) — máquina de estados de la verificación, sin contexto de Spring |
| **Precondiciones** | Técnico y administrador registrados por la API; los documentos se envían dentro de la prueba |
| **Resultados observables** | Estado de verificación, contenido y orden de la cola de revisión, autorización por rol, perfil público resultante |
| **Estado** | **Trazable.** Es el flujo mejor cubierto de los cuatro |

### UC-13 — Reporte de gastos de mantenimiento

| | |
| --- | --- |
| **Superficie** | `GET /analytics/expenses`, `GET /analytics/expenses/csv` |
| **Contrato propio** | **No existe** |
| **Lo que sí lo ejercita** | `PostgresDigitalPaymentIT` (21 pruebas, pero el reporte solo aparece en un auxiliar `report(actor, total, count)`) |
| **Unitarias** | `payments/application/ExpenseLedgerServiceTest` (3: autorización, periodo vacío, contexto histórico incompleto), `payments/web/ExpenseLedgerContextHttpTest` (1: respuesta de conflicto) |
| **Precondiciones** | Pagos aprobados dentro del periodo, creados por la prueba de pagos digitales |
| **Resultados observables** | `$.total` y `$.count` |
| **Estado** | **Brecha.** Ver abajo |

### UC-19 — Avance del trabajo: agenda, ejecución, evidencia y bitácora

| | |
| --- | --- |
| **Superficie** | `GET /jobs/me`, `GET /jobs/{id}`, `GET /jobs/for-request/{id}`, `POST /jobs/{id}/schedule`, `/start`, `/evidence`, `/complete` |
| **Contrato propio** | **No existe** |
| **Recorrido completo** | `PostgresRecoveredDeliveryIT#transferJobIsScheduledStartedDocumentedClosedAndChatBecomesReadOnly` — una prueba que sí agenda, inicia, documenta y cierra |
| **Cierre del trabajo** | `PostgresJobCompletionIT` (7), `jobs/application/CompleteJobTest` (7), `jobs/JobTest` (4), `jobs/application/OpenAssignedJobTest` (3) |
| **Lectura** | `EarningsHttpContract` ejercita `GET /jobs/me` |
| **Precondiciones** | Cotización aceptada y pago aprobado, construidos por la prueba |
| **Resultados observables** | Estado del trabajo, bitácora, evidencias, efectos financieros del cierre |
| **Estado** | **Brecha de trazabilidad.** Ver abajo |

## Las dos brechas, con precisión

### UC-13: el reporte se verifica en dos de sus diez salidas

`BuildExpenseReport.ExpenseReport` tiene diez campos: `from`, `to`, `total`, `count`,
`previousPeriodTotal`, `previousPeriodCount`, `byProperty`, `byMonth`, `bySpecialty` y `lines`. En
todo el repositorio solo se afirman `total` y `count`.

Es decir, **lo que el requisito nombra como el contenido del reporte —el gasto por inmueble, por mes
y por especialidad, comparado con el periodo anterior— no se verifica**. Tampoco se ejercita
`toCsv`: `/analytics/expenses/csv` aparece una sola vez en todo el árbol de pruebas, dentro de
`OpenApiContractTest`, que comprueba que la ruta esté documentada en el contrato OpenAPI, no que
devuelva lo correcto.

Esto no significa que el reporte esté mal. Significa que si alguien rompe la agrupación por
especialidad o la comparación con el periodo anterior, ninguna prueba se entera.

### UC-19: está cubierto, pero no es trazable

Esta brecha es distinta de la anterior y conviene no confundirlas. El recorrido completo sí existe y
sí pasa: `transferJobIsScheduledStartedDocumentedClosedAndChatBecomesReadOnly` agenda, inicia,
agrega evidencia y cierra. El problema es dónde vive y cómo corre.

Vive en `PostgresRecoveredDeliveryIT`, una clase cuyo asunto es otro —la entrega recuperada— y que
reúne siete recorridos largos de distintos flujos. Nadie que busque "dónde se prueba UC-19" lo
encuentra por el nombre. Y al ser una clase `*IT`, solo corre bajo el perfil `postgres-it`: en una
compilación local normal, el avance del trabajo no se ejercita por HTTP ni una vez.

Comparar con UC-01 y UC-16 deja ver qué falta: no pruebas, sino un `JobHttpContract` que sea dueño
del flujo y lo haga correr en los dos motores, como ya hacen los otros nueve contratos.

## Dónde queda la evidencia

El flujo `Backend CI` (`.github/workflows/backend-ci.yml`) se dispara en `push` a `main`, `develop`
y a las ramas `setup/`, `feature/`, `fix/`, `test/`, `docs/`, `release/` y `hotfix/`, y en cada
*pull request* contra `main` y `develop`.

| Qué | Dónde |
| --- | --- |
| Ejecución de las pruebas | Trabajo **Backend verification**, con `-Ppostgres-it clean verify` sobre Java 21 |
| Resultados y errores | Artefacto **`backend-test-reports`**, con `target/surefire-reports/` y `target/failsafe-reports/` |
| Preservación en fallo | El artefacto se publica con `if: always()`, así que una corrida roja también deja su evidencia |
| Contrato de la API | Trabajo **OpenAPI contract**, que detecta deriva entre el código y el contrato publicado |
| Integridad del repositorio | Trabajo **Repository integrity**: `git diff --check` y la inmutabilidad de las migraciones de Flyway ya aplicadas |

Los informes de Surefire y Failsafe traen, por cada prueba, el nombre de la clase y del método, el
tiempo y el rastro completo del fallo. Eso cubre "enlazar guiones, resultados y errores" sin tener
que construir nada nuevo: basta citar la corrida del CI y el artefacto.

Para la parte de desempeño, la evidencia equivalente vive en el repositorio de pruebas, en
`perf/rnf20/resultados/`, donde cada corrida queda con su fecha, la versión del arnés que la produjo
y la escala real de los datos.

## La parte de interfaz: Playwright

La métrica nombra Playwright, y en `Seb-233/fixup-frontend` ya existe. Conviene registrar aquí su
estado, porque la mitad de la comprobabilidad de los flujos críticos vive en ese repositorio.

| Pieza | Estado |
| --- | --- |
| `e2e/web/flujos-criticos-rnf17.spec.ts` | **Los cuatro flujos de este requisito.** 8 pruebas, todas en verde, corrida del 16 de noviembre |
| `e2e/web/navegacion-por-rol.spec.ts` | Navegación por rol, rutas protegidas, redirecciones, fallas 500/503 y barra móvil. Etiquetada `@smoke` y `@completo` |
| `e2e/browser-certification.spec.ts` | 12 pruebas: ingreso, solicitud, cotización, pago en la pasarela simulada, trabajo, chat, pagos, portafolios, ingresos y gastos |
| `.github/workflows/e2e-demo.yml` | Levanta un ambiente efímero —PostgreSQL, almacenamiento S3, backend compilado desde `develop` con perfil demo y semilla sintética, y `ng serve`— y publica el artefacto `e2e-navegacion-por-rol` con 14 días de retención |

### Qué cubre el guion de los flujos críticos

| Flujo | Qué verifica desde el navegador | Resultado |
| --- | --- | --- |
| UC-01 | La propietaria crea una remodelación con su requerimiento y la encuentra en la lista | Pasa |
| UC-13 | Los tres desgloses y la comparación con el periodo anterior; el filtro de periodo; la descarga en CSV entrega un archivo con contenido | Pasa |
| UC-16 | El técnico ve estado, especialidades y documentos; el administrador abre la cola de revisión y la propietaria recibe acceso denegado | Pasa |
| UC-19 | El técnico abre el trabajo que la prueba creó y se exige que ofrezca agendar e iniciar, con su bitácora | Pasa |

Dos de estas comprobaciones llegan donde el backend no llega, y por eso valen aparte del contrato
HTTP: el reporte de gastos, cuyas ocho salidas sin verificar quedan cubiertas aquí —incluida la
descarga en CSV, que bajó 2.884 bytes en la corrida—, y UC-19, que en el backend existe escondido en
una clase de otro asunto.

UC-19 no depende de lo que traiga la semilla: la prueba construye su propia precondición por la API
recorriendo el camino real —la propietaria abre la solicitud, el técnico cotiza, ella acepta y el
pago se aprueba en la pasarela simulada—, y el trabajo nace de ahí. Dos detalles que costaron
depurar y conviene no deshacer:

- **El texto de la solicitud decide quién puede cotizarla.** La especialidad la deduce
  `RepairSpecialtyClassifier` por palabras clave, y el técnico solo puede cotizar si la tiene. El
  título dice "fuga de agua en el lavamanos" para que clasifique como `PLUMBING`, una de las tres
  del técnico sembrado.
- **El trabajo se busca por su título, no se toma el primero de la lista.** El técnico de la
  semilla tiene diecisiete trabajos; la primera versión de la prueba verificaba uno cualquiera. El
  título lleva marca de tiempo y la prueba exige encontrar exactamente uno.

Y una advertencia que vale para cualquier prueba de interfaz que se agregue aquí: los rótulos de
los botones van completos y literales. Una expresión laxa como `/Agendar|Iniciar|Cerrar/` encuentra
el botón de "Cerrar sesión" de la barra lateral y deja la prueba en verde sin haber verificado
nada, que es peor que dejarla en rojo. Pasó, y se detectó revisando la evidencia, no el resultado.

### Un hallazgo del camino: el perfil demo sobre H2 no puede crear solicitudes

`CreateRepairRequest` comprueba la deuda del propietario llamando a
`app_property_owner_has_outstanding_debt`, una función definida en `V38__property_owner_debt_check.sql`,
que vive **solo** en `db/migration-postgres/postgresql/`. Sobre H2 esa función no existe, la consulta
nativa falla y la creación responde 500.

Como crear una remodelación abre una solicitud por requerimiento, el efecto alcanza a UC-01 y a la
prueba de creación de solicitud de la certificación. En la práctica: **el perfil demo arranca sobre
H2 por omisión, y así no sirve para probar estos flujos**. Hay que darle PostgreSQL, que es lo que
`e2e-demo.yml` ya hace en CI y lo que hay que replicar en local.

### Lo que faltaba y ya está hecho

- **`browser-certification.spec.ts` no se ejecutaba en CI**, así que las 12 pruebas que más se
  parecen a los flujos críticos no dejaban evidencia. Ahora corre, como paso informativo
  (`continue-on-error`) hasta confirmar que queda verde en varias corridas seguidas.
- **El disparador solo reaccionaba a cambios de las propias pruebas.** Se agregó `push` en ramas
  `test/**`. Queda pendiente, como decisión del grupo, si vale la pena dispararlo también en los
  *pull request* que tocan `src/**`: cuesta minutos de CI pero es lo que haría que un cambio de la
  aplicación ejecute las pruebas de interfaz.
- **El navegador estaba fijo en Chrome** (`channel: 'chrome'`), así que en cualquier máquina sin
  Chrome en la ruta estándar la suite entera fallaba antes de abrir una sola página. Ahora se elige
  con `PLAYWRIGHT_CHANNEL` (`msedge`, `chromium`), y por omisión sigue siendo Chrome.

## Lo que falta para cerrar RNF-17

Los cuatro flujos críticos están verificados en la interfaz y el backend los cubre en distinto
grado. Lo que queda es profundidad, no cobertura:

1. **Un `JobHttpContract`** que sea dueño de UC-19 en el backend y corra en los dos motores,
   siguiendo el patrón de los otros nueve flujos.
2. **Pruebas del reporte de gastos en el backend** que afirmen las agrupaciones y la comparación con
   el periodo anterior, y que ejerciten la descarga en CSV. Hoy eso solo se verifica desde la
   interfaz, que es mejor que nada pero deja el cálculo sin prueba propia.
3. **Medir el render de la búsqueda** con Playwright, pendiente de RNF-01: su métrica dice "mostrar
   resultados en la interfaz" y lo medido hasta hoy es la API. Requiere sembrarle al ambiente de
   demostración un catálogo grande, porque la semilla sintética es diminuta.

Los dos primeros son trabajo acotado y no tocan código de producción: son pruebas sobre
comportamiento que ya existe.

---

Este documento se actualiza cuando cambie lo que describe. Si una prueba se renombra o se mueve, el
cambio y esta matriz deberían ir en el mismo *commit*: una matriz de trazabilidad desactualizada es
peor que no tenerla, porque afirma una cobertura que ya no existe.
