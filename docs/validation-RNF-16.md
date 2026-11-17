# Validación RNF-16 — Integrabilidad con servicios externos

RNF-16 exige que los adaptadores de servicios externos habilitados intercambien datos de forma
controlada y manejen indisponibilidad, fallos y tiempos de espera. La evidencia a presentar es una
integración externa efectivamente configurada, una solicitud/respuesta válida y una prueba de
tiempo de espera o error con degradación controlada. Aplica a UC-05, UC-09 y UC-17.

El propio enunciado fija la línea roja: la simulación interna de pagos no constituye integración
externa. Aquí se respeta y se deja declarado qué es externo y qué no.

## Qué cuenta como integración externa en este repositorio

| Adaptador | Interfaz de dominio | Implementación | Estado |
| --- | --- | --- | --- |
| Geocodificación de direcciones | `GeocodingProvider` | `NominatimGeocodingProvider` (HTTP real) | **Habilitado**: `fixup.geocoding.enabled=true` por defecto y URL pública real |
| Almacenamiento de objetos | `ObjectStorage` | `S3ObjectStorage` (SDK de AWS, S3/MinIO) | **Habilitado**: `fixup.storage.*` es obligatorio en despliegue |
| Fuente de indicadores de mercado | `MarketIndicatorsSource` | `RestMarketSourceClient` + `TimeoutAndRetryMarketSource` | Adaptador listo, **no habilitado**: en desarrollo y demo se usa la fuente sintética (`provider=development`) |
| Pasarela de pagos | `PaymentGateway` | `SandboxPaymentGateway` | **No es externa**: simulación interna al 100 %, sin salida de red |
| Notificaciones push | `PushNotificationGateway` | `NoopPushNotificationGateway` | Sin proveedor: devuelve `SKIPPED` de forma explícita |

Los dos primeros son las integraciones externas habilitadas y por eso son las que se evidencian. Los
dos últimos quedan declarados como límite: no se afirma entrega de notificaciones ni cobro real.

## 1. Integración externa efectivamente configurada

- Geocodificación: `application.yml` fija `enabled: true`, la URL base
  `https://nominatim.openstreetmap.org`, un `User-Agent` identificable, `connect-timeout: 2s` y
  `read-timeout: 3s`; todo es sustituible por variables de entorno (`FIXUP_GEOCODING_*`).
  `GeocodingConfiguration` construye el cliente con esos tiempos de espera en la fábrica de
  peticiones y entrega el adaptador como bean, así que la aplicación arranca ya integrada.
- Almacenamiento: `fixup.storage.endpoint`, `access-key` y `secret-key` son obligatorios; en el
  despliegue el servicio es MinIO (`k8s/minio-statefulset.yml` y `k8s/minio-bootstrap-job.yml`).

Prueba de que la configuración llega al adaptador: `ExternalProviderDegradationTest` arranca el
contexto real y apunta el adaptador a un extremo propio mediante `@DynamicPropertySource`. Si el
bean no leyera esa configuración, la petición no llegaría y la prueba fallaría.

## 2. Solicitud y respuesta válidas

`ExternalProviderDegradationTest.aConfiguredExternalProviderIsCalledOverRealHttpAndItsPayloadIsMapped`
levanta un proveedor en un socket real, pide `GET /locations/geocode?query=Carrera 7` y comprueba
las dos mitades del intercambio:

- la petición saliente llega con la forma exacta que exige el proveedor configurado: ruta
  `/search`, `q=Carrera 7`, `format=jsonv2`, `limit=5`, `countrycodes=co`, `addressdetails=1` y el
  `User-Agent` de FixUp;
- la respuesta pública es 200 con `displayName`, `latitude` y `longitude` mapeados desde el payload
  del proveedor y con la cabecera obligatoria `X-Data-Attribution`.

Complementos ya existentes en la suite:

- `NominatimGeocodingProviderTest` (8 pruebas) fija la URL, las cabeceras y el mapeo del payload,
  la caché de resultados y el caso de respuesta vacía.
- `evidencias/nominatim/` conserva capturas de llamadas reales al proveedor público: `01` y `02`
  devuelven 200 con direcciones reales de Bogotá, y `03` a `06` documentan los rechazos 401 y 403.
- `MinioStorageIT` recorre subida, publicación, lectura y borrado contra un MinIO real en
  contenedor, es decir, un intercambio real de solicitud y respuesta con el servicio externo.

## 3. Tiempo de espera, fallos e indisponibilidad con degradación controlada

`ExternalProviderDegradationTest` cubre el camino completo con el cliente HTTP real y el tiempo de
espera configurado, y en ningún caso deja que el fallo del proveedor se convierta en un error del
cliente ni en una espera indefinida:

| Escenario del proveedor | Respuesta de FixUp | Qué queda demostrado |
| --- | --- | --- |
| No responde (3 s con `read-timeout` de 1 s) | 503 con detalle público, en menos de 2,5 s | El tiempo de espera corta la llamada: no se agota el hilo |
| 500 interno | 503 con detalle público | El error del proveedor no se propaga |
| Payload ilegible | 503, nunca 4xx | Un proveedor defectuoso no se disfraza de error del cliente |
| 429 del proveedor | 429 controlado | El límite del proveedor se traduce a un código propio |

En los cuatro casos se verifica que el cuerpo no filtre la dirección del proveedor, el puerto, ni
nombres de excepciones o paquetes Java. El adaptador tampoco cachea los fallos: solo se guardan
resultados exitosos.

Otras pruebas de resiliencia que ya estaban en la suite:

- `NominatimGeocodingProviderTest` convierte tiempo de espera y errores de servidor en
  indisponibilidad y comprueba que un intento posterior sí funciona; además respeta la política de
  una petición externa por segundo propia del proveedor (`MINIMUM_REQUEST_INTERVAL`).
- `TimeoutAndRetryMarketSourceTest` (8 pruebas) cubre reintentos, máximo de intentos configurable,
  la propagación del fallo hacia la degradación elegante y el recorte de reintentos y esperas. Usa
  dobles de prueba: no ejerce HTTP real.
- `RestMarketSourceClientTest` (9 pruebas) rechaza payloads incompletos, fuera de rango o con fecha
  de observación futura y los convierte en indisponibilidad, no en 400.
- `MarketIndicatorsHttpContract` prueba a nivel HTTP la degradación de la fuente externa: sirve el
  último valor conocido con `freshness=DEGRADED` y `degraded=true`, y responde 503
  `INDICATORS_UNAVAILABLE` cuando no hay valor previo. Sustituye la fuente de dominio, así que mide
  el comportamiento del caso de uso, no el del adaptador HTTP.
- `MediaDeletionWorkerTest.storageFailureLeavesJobRecoverableAndWorkerRetriesToCompleted` prueba que
  un fallo del almacenamiento deja el trabajo recuperable (`FAILED`, intento contabilizado), que el
  objeto no se pierde y que tras la espera de reintento el trabajo termina en `COMPLETED`.
  `MediaDeletionJobFinalizer` clasifica el fallo en `STORAGE_TIMEOUT` o `STORAGE_UNAVAILABLE` sin
  exponer la URL firmada.

## Ejecución

Prueba de extremo a extremo del adaptador externo con tiempo de espera y errores:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress "-Dtest=ExternalProviderDegradationTest" test
```

Pruebas de los adaptadores de geocodificación y de la fuente de mercado:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress "-Dtest=NominatimGeocodingProviderTest,RestMarketSourceClientTest,TimeoutAndRetryMarketSourceTest" test
```

Intercambio real con el almacenamiento (requiere Docker):

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress test-compile failsafe:integration-test failsafe:verify "-Dit.test=MinioStorageIT"
```

## Resultado verificado

| Comprobación | Comando | Resultado |
| --- | --- | --- |
| Tiempo de espera y errores con degradación controlada | `-Dtest=ExternalProviderDegradationTest test` | 5 pruebas, 0 fallos, 0 errores |
| Adaptadores de geocodificación y fuente de mercado | `-Dtest=NominatimGeocodingProviderTest,RestMarketSourceClientTest,TimeoutAndRetryMarketSourceTest test` | 25 pruebas, 0 fallos, 0 errores |
| Intercambio real con MinIO en contenedor | `-Dit.test=MinioStorageIT` | No re-ejecutada localmente: ver nota de Docker |

Nota de Docker: `MinioStorageIT` usa la imagen `ghcr.io/seb-233/fixup-minio`, que en la integración
continua se descarga autenticada (`docker/login-action` contra `ghcr.io` con `GITHUB_TOKEN`) antes
de `./mvnw -Ppostgres-it clean verify`. Sin esa autenticación, la descarga local de la imagen no
completa y la prueba no puede ejecutarse; no es un fallo de la prueba ni del adaptador. Las pruebas
de tiempo de espera y errores sobre HTTP real no dependen de Docker y quedaron verificadas arriba.

## Demostración en vivo

1. Degradación controlada, sin depender de que Internet falle:
   arrancar el backend con `FIXUP_GEOCODING_BASE_URL` apuntando a una dirección reservada que no
   responde (por ejemplo `http://192.0.2.1`) y pedir
   `GET /locations/geocode?query=Carrera%207` con un token válido. La respuesta debe ser 503 con
   detalle `Address geocoding is temporarily unavailable`, sin esperar más que el tiempo de espera
   de conexión configurado (2 s).
2. Intercambio real con el proveedor: arrancar con la configuración por defecto y repetir la misma
   petición. Debe devolver 200 con candidatos reales y la cabecera `X-Data-Attribution`.
3. Prueba automatizada: ejecutar el primer comando de la sección anterior y mostrar los cinco
   escenarios en verde, incluido el caso en que el proveedor tarda más que el tiempo de espera.

## Límites de alcance

- La prueba de tiempo de espera usa un extremo local que hace de proveedor: el adaptador, la
  configuración y el cliente HTTP son los de producción; lo simulado es el extremo remoto, porque
  no es posible pedirle a un proveedor público que deje de responder a demanda.
- La fuente de mercado está implementada y probada, pero no habilitada: en desarrollo y demo se
  sirve la fuente sintética. Su resiliencia está medida con dobles de prueba, no sobre HTTP real.
- No hay integración con proveedor de pagos ni de notificaciones push. La pasarela de pagos es
  interna y la de push devuelve `SKIPPED`; no se afirma entrega a dispositivos.
