-- US-171: inicio y fin del viaje que marca el conductor (o el fin forzado por el operador).
ALTER TABLE fleet_itineraries
    ADD COLUMN iniciado_en   TIMESTAMP WITH TIME ZONE,
    ADD COLUMN finalizado_en TIMESTAMP WITH TIME ZONE,
    ADD COLUMN iniciado_por  UUID;

-- Checklist que el conductor confirma antes de salir (una por viaje).
CREATE TABLE trip_checklists (
    itinerary_id  UUID PRIMARY KEY REFERENCES fleet_itineraries (itinerary_id) ON DELETE CASCADE,
    conductor_id  UUID NOT NULL,
    items         JSONB NOT NULL,
    observacion   VARCHAR(300),
    creado_en     TIMESTAMP WITH TIME ZONE NOT NULL
);

-- US-176: tipos de novedad graves y SOS.
ALTER TABLE trip_events DROP CONSTRAINT ck_trip_events_tipo;
ALTER TABLE trip_events ADD CONSTRAINT ck_trip_events_tipo CHECK (tipo IN (
    'INICIO', 'LLEGADA_PARADA', 'RETRASO', 'DESVIO', 'FIN', 'AVERIA', 'ACCIDENTE', 'VIA_CERRADA', 'SOS'));

-- US-176: alertas para el operador de la empresa (tópico privado, nunca la línea de tiempo pública).
CREATE TABLE trip_alerts (
    id               UUID PRIMARY KEY,
    itinerary_id     UUID NOT NULL REFERENCES fleet_itineraries (itinerary_id) ON DELETE CASCADE,
    company_id       UUID,
    bus_id           UUID NOT NULL,
    tipo             VARCHAR(20) NOT NULL,
    gravedad         VARCHAR(10) NOT NULL,
    lat              DOUBLE PRECISION,
    lng              DOUBLE PRECISION,
    mensaje          VARCHAR(300),
    puede_continuar  BOOLEAN,
    estado           VARCHAR(10) NOT NULL,
    creado_por       UUID,
    creado_en        TIMESTAMP WITH TIME ZONE NOT NULL,
    resuelto_por     UUID,
    resuelto_en      TIMESTAMP WITH TIME ZONE,
    nota_resolucion  VARCHAR(300),
    CONSTRAINT ck_trip_alerts_tipo CHECK (tipo IN ('AVERIA', 'ACCIDENTE', 'VIA_CERRADA', 'SOS')),
    CONSTRAINT ck_trip_alerts_gravedad CHECK (gravedad IN ('NORMAL', 'ALTA', 'CRITICA')),
    CONSTRAINT ck_trip_alerts_estado CHECK (estado IN ('ABIERTA', 'RESUELTA'))
);

CREATE INDEX idx_trip_alerts_itinerary ON trip_alerts (itinerary_id, creado_en);
CREATE INDEX idx_trip_alerts_company ON trip_alerts (company_id, estado, creado_en);
-- Un solo SOS abierto por viaje: si el conductor lo repite antes de que lo resuelvan, es el mismo.
CREATE UNIQUE INDEX uq_trip_alerts_sos_abierto ON trip_alerts (itinerary_id)
    WHERE tipo = 'SOS' AND estado = 'ABIERTA';
