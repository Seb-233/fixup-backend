# Validación FR-UC-01 — remodelación multiprofesional

FR-UC-01 agrega el módulo `remodelings` para agrupar varias necesidades de una
propiedad sin alterar el flujo individual de reparaciones. Cada requirement crea un
`RepairRequest` normal y conserva la cardinalidad existente: una cotización ganadora,
un fixer asignado, un job y un ingreso por request.

## Contrato HTTP

| Método | Ruta | Resultado |
| --- | --- | --- |
| POST | `/remodelings` | Crea atómicamente el proyecto y sus requests; 201 |
| GET | `/remodelings/me` | Lista los proyectos del OWNER autenticado; 200 |
| GET | `/remodelings/{projectId}` | Devuelve detalle y progreso derivado; 200 |

La creación exige un usuario ACTIVE con rol OWNER persistido y propiedad directa del
inmueble. TENANT, FIXER, REAL_ESTATE_MANAGER y PLATFORM_ADMIN no crean proyectos. El
OWNER solo lee los propios; PLATFORM_ADMIN tiene lectura de soporte; FIXER no obtiene
el agregado completo. Los roles declarados solamente en el JWT no conceden acceso.

## Modelo y transacción

- `remodeling_projects` guarda propiedad, propietario, título, descripción y fechas.
- `remodeling_requirements` guarda orden y referencia al request, sin duplicar
  especialidad, asignación, urgencia, medios ni job.
- La apertura de todos los requests y la persistencia del proyecto comparten una sola
  transacción. Si una necesidad falla, no queda ningún request parcial.
- El progreso no se persiste. Se deriva en lote desde requests y jobs como `OPEN`,
  `PARTIALLY_ASSIGNED`, `FULLY_ASSIGNED`, `PARTIALLY_COMPLETED` o `COMPLETED`.

## Fronteras modulares

`remodelings` usa `PropertyDirectory`, `RepairRequestCreation`, el directorio batch de
requests y `JobDirectory`. No accede a entidades ni repositorios ajenos. La
clasificación de especialidad sigue dentro de requests; la aceptación de cotizaciones,
el locking, la creación de jobs e ingresos y el chat por request permanecen sin cambios.

## PostgreSQL

- `V20__remodeling_projects.sql` crea tablas, FKs, checks, unicidad e índices.
- `V21__remodeling_projects_rls.sql` activa y fuerza RLS sobre ambas tablas.
- Las políticas permiten lectura al propietario o PLATFORM_ADMIN e inserción al
  propietario; requirements hereda visibilidad y autorización desde el proyecto.
- No existe UPDATE ni DELETE del agregado en este incremento.

## Evidencia automatizada

Las pruebas cubren invariantes y los cinco estados, autorización HTTP, propiedad ajena,
rol JWT falsificado, clasificación, rollback total, flujo de cotizaciones/jobs/ingresos,
un mismo fixer en varias necesidades, fixers distintos en necesidades concurrentes,
migraciones, restricciones, índices y RLS real. `ModularityTest` y `LayerRulesTest`
verifican las fronteras del decimoquinto módulo. `OpenApiContractTest` regenera
`docs/openapi.json` desde Springdoc.

## Deuda fuera de alcance

No se agregaron cancelación, reasignación, estado STARTED/IN_PROGRESS, presupuesto,
quotation/job/pago/chat global ni administración por TENANT o REAL_ESTATE_MANAGER. La
deuda existente de notifications no bloquea este caso y no se amplió.
