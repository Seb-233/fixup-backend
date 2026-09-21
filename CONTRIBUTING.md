# Contribuir a FixUp Backend

## Ramas

`main` conserva la versión estable y `develop` integra el trabajo del equipo. No realizar push directo a ninguna de estas ramas; los cambios ingresan mediante pull requests revisados.

Crear ramas temporales desde `develop` según su propósito:

- `setup/<descripcion>`: configuración y estructura.
- `feature/<descripcion>`: funcionalidades.
- `fix/<descripcion>`: correcciones.
- `test/<descripcion>`: pruebas.
- `docs/<descripcion>`: documentación.
- `release/<version>`: preparación de versiones.
- `hotfix/<descripcion>`: correcciones urgentes de la versión estable.

Las ramas de release y hotfix pueden partir de la referencia acordada para la versión. Sus cambios deben integrarse por PR en las ramas permanentes correspondientes para evitar divergencias. No hacer force push ni reescribir historia en ramas compartidas.

## Commits

Usar Conventional Commits con cambios coherentes y firmados:

```text
feat(users): add approved use case
fix(security): correct access policy
test(architecture): verify module boundaries
ci(backend): update verification workflow
docs(repository): update local setup
```

Usar nombres de paquetes, clases, métodos y variables en inglés. La documentación puede escribirse en español. Respetar `.editorconfig`, `.gitattributes` y las reglas arquitectónicas.

## Pull requests y revisión

1. Acordar el alcance y los contratos antes de implementar funcionalidades.
2. Crear una rama temporal y mantener los cambios dentro del alcance.
3. Ejecutar las validaciones y revisar todos los archivos staged.
4. Publicar la rama y abrir el PR hacia `develop`, describiendo el problema, el cambio y las pruebas.
5. Solicitar revisión de otro integrante y atender los comentarios.
6. Integrar únicamente tras aprobación y CI exitoso. No autoaprobar el PR.

Las entregas estables se integran a `main` mediante PR revisado. Los pendientes se registran en Issues o en el tablero, sin convertir el README en un reporte de progreso.

## Validaciones obligatorias

```bash
./mvnw clean verify
git status --short
git diff --check
git diff --cached --check
git diff --cached
git ls-files | grep -E '(^|/)\.env($|\.)|\.pem$|\.key$|\.p12$|\.pfx$|\.jks$|\.keystore$|service-account.*\.json$'
```

En PowerShell, ejecutar `.\mvnw.cmd clean verify` y usar `Select-String` para la comprobación de nombres sensibles. El listado sensible solo puede incluir `.env.example`.

Revisar además el contenido: ausencia de credenciales y datos personales, fronteras modulares, contratos públicos, cobertura pertinente y documentación. No versionar archivos generados. Después de confirmar los cambios, `git status --short` debe quedar vacío.

La CI comprueba la compilación, las pruebas y la arquitectura; la revisión humana evalúa el alcance, la propiedad de datos y las decisiones de diseño.
