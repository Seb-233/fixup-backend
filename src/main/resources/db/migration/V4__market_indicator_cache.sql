-- FR-UC-15: caché de indicadores del mercado inmobiliario por zona.
-- Owned by analytics. No JPA association exposes another module's entity.
--
-- Esta tabla es lo que sostiene la degradación elegante: cuando la fuente externa no responde,
-- el último valor conocido de la zona se sirve marcado como degradado.

CREATE TABLE market_indicator_snapshots (
    zone VARCHAR(64) PRIMARY KEY,
    price_per_square_meter NUMERIC(15, 2) NOT NULL,
    year_over_year_variation_percent NUMERIC(6, 2) NOT NULL,
    average_days_on_market INTEGER NOT NULL,
    -- Marca de frescura: cuándo la fuente produjo el dato, no cuándo se guardó.
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    cached_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Quién produjo el dato. Se guarda junto al valor para que un número sintético siga
    -- declarándose sintético cuando después se sirva desde la caché o degradado.
    source VARCHAR(32) NOT NULL,
    CONSTRAINT ck_market_zone CHECK (length(trim(zone)) > 0),
    CONSTRAINT ck_market_price CHECK (price_per_square_meter > 0),
    CONSTRAINT ck_market_days CHECK (average_days_on_market >= 0),
    CONSTRAINT ck_market_source CHECK (source IN ('EXTERNAL_PROVIDER', 'DEVELOPMENT_SYNTHETIC'))
);
