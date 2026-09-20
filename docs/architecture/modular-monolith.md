# Arquitectura del monolito modular

FixUp es una aplicación Spring Boot bajo com.fixup. Cada subpaquete directo constituye
un módulo cerrado. Se conservan 14 módulos; no hay microservicios ni propagación de JWT
entre módulos internos.

## Identidad y permisos

La cadena Spring Security valida firma RS256, issuer, audience y tiempo antes del
controlador. La adaptación de identidad en identityaccess.infrastructure lee únicamente
el JwtAuthenticationToken ya autenticado y su subject.

CurrentActorProvider consulta users/user_roles, exige estado ACTIVE y devuelve un
contrato inmutable. Los controladores entregan ese actor a los casos de uso.
Ningún rol del frontend o del token sustituye los permisos almacenados.

## Dependencias activas

| Módulo | Datos propios | Contratos consumidos |
| --- | --- | --- |
| shared | Ningún dato de negocio | Ninguno de negocio |
| identityaccess | users y user_roles | shared.errors |
| fixers | fixer_profiles | identityaccess.api y shared.errors |

identityaccess publica RoleGranted en la transacción. fixers lo consume síncronamente
para crear un perfil PENDING cuando se concede FIXER. Así mantiene la propiedad de
sus datos sin crear una dependencia circular.

La base es única, con migraciones Flyway y validación de esquema por Hibernate.
La cuenta usa UUID interno y subject externo único. La elección inicial de roles
bloquea la cuenta durante la actualización, y el bootstrap resuelve carreras apoyado
en la restricción única y una transacción nueva.

## Límites

El módulo users mantiene su estructura reservada para futuros casos de perfil; no
duplica el modelo de identidad. Los demás módulos no reciben funcionalidades de negocio
en esta entrega. FixerEligibility prepara el control necesario para trabajos futuros,
sin implementar esos casos de uso ni la revisión administrativa de trabajadores.

Consulta las [reglas de módulos](../module-rules.md), la
[guía de autenticación](../authentication.md) y el [contrato OpenAPI](../openapi.json).
