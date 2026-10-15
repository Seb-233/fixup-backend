# Validación FR-UC-06 — coordenadas en la creación de propiedades

## Corrección

`POST /properties` exige `latitude` y `longitude`, valida sus rangos y los entrega al
dominio para que la propiedad `PUBLISHED` quede geolocalizada desde su creación. El
backend no geocodifica silenciosamente: el cliente debe seleccionar previamente un
candidato obtenido mediante `GET /locations/geocode`.

El overload histórico de `Property.create` sin coordenadas se conserva para flujos
internos existentes, incluida la importación masiva. No se modificó la semántica de
`GET /properties/search`, sus filtros `ALL`, `MINE` y `OTHERS`, ni las políticas RLS.

## Registros históricos

No se agregó migración, `NOT NULL`, backfill ni coordenadas artificiales. Las
propiedades publicadas que ya tienen coordenadas nulas continuarán excluidas de las
búsquedas geográficas hasta que sus datos se corrijan con información real.

Consulta de diagnóstico:

```sql
SELECT
    id,
    owner_user_id,
    name,
    address,
    city,
    status,
    latitude,
    longitude
FROM properties
WHERE status = 'PUBLISHED'
  AND (latitude IS NULL OR longitude IS NULL);
```

## Regresión crítica

La integración de propiedades crea una propiedad mediante `POST /properties` como
OWNER A y después la busca como OWNER B con `ownershipScope=ALL` y `OTHERS`. En ambos
casos comprueba que aparece dentro del radio y que `ownedByCurrentUser` es `false`.
