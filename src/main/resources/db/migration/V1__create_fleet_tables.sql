-- Fleet + Trip Events (esquema propio del servicio).

-- Itinerarios conocidos (evento ItineraryPublished) para asociar buses a viajes.
CREATE TABLE fleet_itineraries (
    itinerary_id          UUID PRIMARY KEY,
    bus_id                UUID NOT NULL,
    route_id              UUID NOT NULL,
    company_id            UUID,
    origen                VARCHAR(80),
    destino               VARCHAR(80),
    fecha_salida          TIMESTAMP WITH TIME ZONE NOT NULL,
    fecha_llegada         TIMESTAMP WITH TIME ZONE NOT NULL,
    cancelado             BOOLEAN NOT NULL DEFAULT FALSE,
    -- Progreso del simulador GPS: índice de la última parada anunciada (-1 = no ha salido).
    sim_ultima_parada     INTEGER NOT NULL DEFAULT -1
);

CREATE INDEX idx_fleet_itineraries_ventana ON fleet_itineraries (fecha_salida, fecha_llegada);
CREATE INDEX idx_fleet_itineraries_bus ON fleet_itineraries (bus_id);

CREATE TABLE route_stops (
    route_id              UUID NOT NULL,
    orden                 INTEGER NOT NULL,
    nombre                VARCHAR(120) NOT NULL,
    lat                   DOUBLE PRECISION NOT NULL,
    lng                   DOUBLE PRECISION NOT NULL,
    minutos_desde_salida  INTEGER NOT NULL,
    PRIMARY KEY (route_id, orden)
);

-- Serie temporal de posiciones (TimescaleDB es opcional en el MVP: PostgreSQL + índices).
CREATE TABLE bus_positions (
    id             BIGSERIAL PRIMARY KEY,
    event_id       VARCHAR(64) NOT NULL,
    bus_id         UUID NOT NULL,
    itinerary_id   UUID,
    lat            DOUBLE PRECISION NOT NULL,
    lng            DOUBLE PRECISION NOT NULL,
    velocidad      DOUBLE PRECISION,
    rumbo          DOUBLE PRECISION,
    registrado_en  TIMESTAMP WITH TIME ZONE NOT NULL,
    recibido_en    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_bus_positions_event UNIQUE (event_id)
);

CREATE INDEX idx_bus_positions_bus ON bus_positions (bus_id, registrado_en DESC);
CREATE INDEX idx_bus_positions_itinerary ON bus_positions (itinerary_id, registrado_en DESC);

CREATE TABLE trip_events (
    id            UUID PRIMARY KEY,
    itinerary_id  UUID NOT NULL,
    tipo          VARCHAR(20) NOT NULL,
    descripcion   VARCHAR(300),
    payload       TEXT,
    user_id       UUID,
    origen        VARCHAR(20) NOT NULL,
    ocurrido_en   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_trip_events_tipo CHECK (tipo IN ('INICIO', 'LLEGADA_PARADA', 'RETRASO', 'DESVIO', 'FIN'))
);

CREATE INDEX idx_trip_events_itinerary ON trip_events (itinerary_id, ocurrido_en);

-- Transactional outbox: el evento se guarda en la MISMA transacción que el cambio de estado.
CREATE TABLE outbox (
    id            UUID PRIMARY KEY,
    event_type    VARCHAR(80) NOT NULL,
    payload       TEXT NOT NULL,
    creado_en     TIMESTAMP WITH TIME ZONE NOT NULL,
    publicado_en  TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_outbox_pendientes ON outbox (creado_en) WHERE publicado_en IS NULL;

CREATE TABLE processed_events (
    event_id      VARCHAR(64) PRIMARY KEY,
    event_type    VARCHAR(80) NOT NULL,
    processed_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
