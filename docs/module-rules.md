# Reglas del monolito modular

1. Cada módulo es dueño de sus datos y reglas.
2. Ningún módulo accede a repositorios internos ajenos.
3. No importar domain, application, infrastructure ni web de otro módulo.
4. La comunicación síncrona entre módulos usa contratos de api, declarada NamedInterface.
5. Los eventos permiten coordinar cambios sin invertir las dependencias entre módulos.
6. shared contiene únicamente elementos técnicos reutilizables.
7. shared no contiene reglas de negocio ni depende de módulos de negocio.
8. Los controladores usan casos de uso; no acceden a JPA ni validan JWT manualmente.
9. Las entidades JPA no se comparten entre módulos ni forman contratos públicos.
10. No se permiten ciclos.
11. Spring Modulith verifica fronteras y ciclos durante la verificación.
12. Los casos de uso reciben CurrentActor; no reciben JWT de otros módulos.

Los módulos son cerrados. Los paquetes api tienen interfaz nombrada. shared.errors
expone una interfaz técnica de respuestas HTTP; shared.security permanece interno.

## Capas activas

- api: contratos públicos e inmutables, enums y eventos sin entidades JPA.
- web: adaptación HTTP y Bean Validation, con controladores delgados.
- application: coordinación transaccional y autorización del caso de uso.
- domain: modelos, puertos y políticas propios del módulo.
- infrastructure: persistencia y adaptación de servicios técnicos.

identityaccess mantiene users/user_roles y obtiene el actor desde una identidad JWT
validada por Spring Security. fixers mantiene su perfil separado y consume RoleGranted
por la API de identityaccess. Su listener síncrono participa en la transacción de
asignación del rol: no hay llamada de identityaccess hacia internals de fixers.

La seguridad técnica valida JWT y acceso general; los casos de uso consultan roles
internos y estado. La autorización administrativa combina PreAuthorize con la política
de dominio. Los futuros casos de uso de trabajos deben invocar FixerEligibility.

## Verificación

ModularityTest exige los 14 módulos y ejecuta ApplicationModules.verify().
LayerRulesTest impide dependencias de shared hacia negocio y dependencias de web/api
hacia JPA, Spring Data o infrastructure. Las pruebas funcionales se ejecutan con
migraciones y se repiten contra PostgreSQL con el perfil Maven postgres-it.
