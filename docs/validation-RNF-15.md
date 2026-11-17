# Validación RNF-15 — Contrato OpenAPI versionado entre frontend y backend

RNF-15 exige que el intercambio frontend–backend respete un contrato OpenAPI versionado y
compatible con la aplicación desplegada. La evidencia a presentar es la generación del cliente, la
compatibilidad de rutas y esquemas, las pruebas de contrato y el rechazo controlado ante
incompatibilidades. Aplica a UC-01, UC-12, UC-13 y UC-16.

## Cómo se sostiene el contrato

- Springdoc publica el contrato OpenAPI 3.1 en ejecución y `docs/openapi.json` es el snapshot
  versionado, con `info.version = v1`.
- `OpenApiContractTest` compara el contrato en ejecución contra el snapshot y comprueba además que
  no introduzca cambios incompatibles. El snapshot solo se reescribe si la prueba se ejecuta con
  `-Dfixup.openapi.update-snapshot=true`, de modo que una actualización del contrato es siempre una
  decisión explícita y revisable en el diff.
- `OpenApiCompatibility` es el comparador: recorre `info.version`, rutas, métodos, `operationId`,
  seguridad, parámetros, cuerpo de petición, respuestas, esquemas, tipos, formatos, enumerados y
  propiedades, y devuelve la lista de cambios que rompen compatibilidad.
- El cliente Angular se genera con OpenAPI Generator 7.25.0 desde `fixup-frontend/scripts/generate-api.mjs`.
  `npm run api:generate` escribe el cliente y `npm run api:check` genera en una carpeta temporal y
  compara: no deja artefactos en el árbol ni modifica el cliente versionado.
- Integración continua: el job `openapi-contract` del backend compara `docs/openapi.json` con el
  contrato generado, y el job `api-contract` del frontend regenera el cliente desde el contrato del
  backend y falla enumerando los archivos desincronizados.

## Qué se verificó

| Comprobación | Comando | Resultado |
| --- | --- | --- |
| Reglas de compatibilidad | `-Dtest=OpenApiCompatibilityTest test` | 5 pruebas, 0 fallos |
| Contrato en ejecución contra el snapshot | `-Dtest=OpenApiContractTest test` | 1 prueba, 0 fallos (PostgreSQL 17 con Testcontainers) |
| Cliente generado sincronizado | `npm run api:check` | El cliente coincide con el contrato |
| Aplicación compilable con el cliente regenerado | `npm run build`, `npm run lint` | Correcto |

Los cinco escenarios de `OpenApiCompatibilityTest` son: un campo opcional añadido a una respuesta se
acepta sin romper compatibilidad; una ruta eliminada se detecta; un cambio de tipo en una respuesta
se detecta; una propiedad requerida nueva en una petición se detecta; y un cambio de
`info.version` se detecta.

## Estado frente a la aplicación desplegada

`main` del backend todavía no expone `/administration/audit/status-changes`; esa ruta vive en la
rama de la bitácora de transiciones. Mientras siga así, el par desplegado (backend `main` más el
cliente del frontend) no coincide y el chequeo reporta deriva: es el comportamiento correcto, no un
fallo. Por eso el job valida contra la rama homónima del backend cuando existe y, en su defecto,
contra `main`, que es lo desplegado. Añadir la ruta es un cambio aditivo, así que `v1` sigue siendo
válido y no hay que subir de versión.

## Límites de alcance

- La verificación es de estructura del contrato, no de comportamiento: no sustituye a las pruebas
  funcionales ni de seguridad de cada endpoint.
- El snapshot cubre el esquema publicado; los modelos que el contrato no expone como esquema
  utilizable siguen resolviéndose con los tipos propios del frontend.
