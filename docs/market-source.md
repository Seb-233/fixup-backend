# Fuente de indicadores de mercado (FR-UC-15)

El equipo todavía no tiene proveedor de datos inmobiliarios contratado. Esto describe qué se sirve
en cada entorno y por qué la respuesta declara siempre su procedencia.

## Selección del adaptador

`fixup.analytics.market-source.provider` no tiene valor implícito: hay que declararla.

| Valor | Requisitos | Qué se sirve |
| --- | --- | --- |
| `rest` | `base-url` configurada | Datos del proveedor externo, `source: EXTERNAL_PROVIDER` |
| `development` | además el perfil `dev` | Números sintéticos, `source: DEVELOPMENT_SYNTHETIC` |
| sin declarar | — | Ninguna fuente: 503, o el último valor conocido marcado `DEGRADED` |

Antes, el respaldo de desarrollo se activaba **al faltar configuración** (`matchIfMissing = true`) y
respondía igual que una fuente real. Un entorno mal configurado servía números inventados como si
fueran observaciones del mercado. Ahora falta de configuración produce indisponibilidad, que es la
verdad, y el respaldo sintético exige perfil `dev` y declaración explícita. Además, su cálculo usa
`Math.floorMod` para asegurar números deterministas y no negativos incluso ante colisiones o `Integer.MIN_VALUE`.

`compose.development.yml` levanta el backend con `SPRING_PROFILES_ACTIVE=dev`, así que el entorno
local sigue teniendo datos con los que demostrar el caso de uso.

## Procedencia y frescura son cosas distintas

- `freshness` dice **cómo** se obtuvo el valor: `LIVE` en esta petición, `CACHED` de una caché
  vigente, `DEGRADED` del último valor conocido porque la fuente no respondió.
- `source` dice **quién** lo produjo: `EXTERNAL_PROVIDER` o `DEVELOPMENT_SYNTHETIC`.
- `synthetic` repite lo segundo como bandera para el cliente.

Un número sintético recién generado es `LIVE`, y decir solo `LIVE` haría creer al cliente que es una
observación del mercado. La procedencia se guarda junto al valor en `market_indicator_snapshots`, de
modo que un valor sintético sigue declarándose sintético cuando después se sirve como `CACHED` o
`DEGRADED`.

## Validación defensiva del proveedor

`observedAt` es el momento en que la fuente produjo el dato. Si el proveedor no lo entrega, el
payload se considera incompleto y se trata como indisponibilidad. Rellenarlo con `Instant.now()`
convertiría un dato de antigüedad desconocida en uno aparentemente recién observado y la frescura
dejaría de significar algo.

Adicionalmente, el adaptador REST valida exhaustivamente:
- Presencia obligatoria de `pricePerSquareMeter`, `yearOverYearVariationPercent`, `averageDaysOnMarket` (como `Integer`) y `observedAt`.
- Precio positivo y dentro del límite persistible `NUMERIC(15, 2)`.
- Variación dentro del límite persistible `NUMERIC(6, 2)`.
- Días en mercado no negativos.
- Marca temporal no futura (tolerancia máxima de 5 minutos).
- Precisión decimal estricta (máximo 2 decimales sin redondeo silencioso).

Cualquier inconsistencia en el payload del proveedor se traduce en `MarketSourceUnavailableException`
para activar degradación elegante:
- Si existe caché previa: **200 DEGRADED**.
- Si no existe caché previa: **503 INDICATORS_UNAVAILABLE**.
- **Nunca se emite 400 por fallos del proveedor**: el código 400 queda reservado exclusivamente para solicitudes donde la zona enviada por el cliente es sintácticamente inválida.

## Caché concurrente y monotónica

- La lectura y la escritura de la caché ocurren en transacciones cortas e independientes, declaradas
  en el adaptador de persistencia.
- La persistencia atómica en PostgreSQL implementa un upsert monotónico:
  ```sql
  INSERT INTO market_indicator_snapshots (
      zone, price_per_square_meter, year_over_year_variation_percent,
      average_days_on_market, observed_at, cached_at, source
  )
  VALUES (?, ?, ?, ?, ?, ?, ?)
  ON CONFLICT (zone) DO UPDATE
  SET price_per_square_meter = EXCLUDED.price_per_square_meter,
      year_over_year_variation_percent = EXCLUDED.year_over_year_variation_percent,
      average_days_on_market = EXCLUDED.average_days_on_market,
      observed_at = EXCLUDED.observed_at,
      cached_at = EXCLUDED.cached_at,
      source = EXCLUDED.source
  WHERE EXCLUDED.observed_at >= market_indicator_snapshots.observed_at
  ```
- Este mecanismo asegura que:
  1. Dos solicitudes concurrentes iniciales sobre la misma zona no producen errores de clave primaria ni respuestas 500.
  2. Queda una única fila por zona.
  3. Un dato con `observedAt` anterior nunca sobreescribe una observación más reciente.
  4. `cached_at` y `source` corresponden estrictamente al dato aceptado.
- La llamada HTTP, sus reintentos y sus esperas ocurren **fuera** de toda transacción: sostener una
  transacción de PostgreSQL durante segundos retiene una conexión del pool sin usarla.
- La espera entre reintentos bloquea el hilo que atiende la petición. Se acepta para el alcance
  académico, pero acotada: `max-retries` y `retry-delay` son configurables y además se recortan en
  código a 3 reintentos y 500 ms, así ninguna configuración puede inmovilizar un hilo del servidor.
- El log de reintentos lleva solo zona e intento. Ni la URL del proveedor, ni sus credenciales, ni el
  detalle de su error salen en los registros.
