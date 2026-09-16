# Arquitectura del monolito modular

FixUp se ejecuta como una aplicación Spring Boot bajo el paquete raíz `com.fixup`. Spring Modulith interpreta cada subpaquete directo como un módulo cerrado.

## Reglas de módulos

1. Cada módulo es dueño de sus datos y reglas.
2. Ningún módulo accede a repositorios internos ajenos.
3. No importar `domain`, `application`, `infrastructure` ni `web` de otro módulo.
4. La comunicación síncrona futura utiliza exclusivamente `api`, declarada como NamedInterface de Spring Modulith.
5. Los efectos secundarios futuros se coordinan mediante eventos.
6. `shared` contiene únicamente elementos técnicos realmente reutilizables.
7. No colocar reglas del negocio en `shared`; tampoco puede depender de los módulos del negocio.
8. Los controladores no acceden directamente a JPA ni a repositorios; usan la capa de aplicación.
9. Las entidades JPA no se comparten entre módulos ni forman contratos públicos.
10. No se permiten dependencias circulares.
11. Spring Modulith verifica fronteras y ciclos en cada build de CI.
12. Ningún módulo se utiliza como contenedor de código sin clasificar.

Los módulos son cerrados. Solo los paquetes `api` tienen interfaz nombrada; las raíces se reservan para metadatos, no para clases públicas del negocio. Los subpaquetes de `shared` son privados por defecto; exponer una interfaz técnica específica exige una decisión explícita posterior.

## Capas

- `api`: contratos públicos futuros, sin entidades ni repositorios.
- `application`: coordinación futura de casos de uso.
- `domain`: reglas y modelos futuros propiedad del módulo.
- `infrastructure`: adaptadores y persistencia futuros.
- `web`: adaptadores HTTP futuros basados en contratos aprobados.

Los paquetes reservados se conservan con `package-info.java`. La configuración de seguridad en `shared.security` deniega solicitudes sin implementar autenticación.

## Verificación

`ModularityTest` comprueba el conjunto exacto de módulos para evitar verificaciones vacías y ejecuta `ApplicationModules.of(FixupApplication.class).verify()`. La encapsulación cierra el acceso a internals ajenos. Una regla adicional de ArchUnit impide dependencias de `shared` hacia módulos del negocio.

`LayerRulesTest` añade restricciones para que web y api no dependan de JPA, Spring Data o infraestructura. El sentido del negocio, la propiedad de datos y la clasificación del código también requieren revisión humana; no se presentan como garantías automáticas.
