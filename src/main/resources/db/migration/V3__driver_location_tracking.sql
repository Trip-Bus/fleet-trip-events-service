-- US-173: la posición la manda el celular del conductor. conductor_id queda solo en la base (nunca va
-- en PositionUpdated); es NULL para el simulador GPS y para las posiciones anteriores.
ALTER TABLE bus_positions
    ADD COLUMN conductor_id UUID,
    ADD COLUMN precision    DOUBLE PRECISION;

-- US-174: estado del rastreo de cada viaje (ACTIVO, EN_PAUSA, SIN_SENAL, FINALIZADO).
-- ultima_lat/ultima_lng: última lectura aceptada, para descartar saltos imposibles (US-173).
CREATE TABLE tracking_sessions (
    itinerary_id        UUID PRIMARY KEY REFERENCES fleet_itineraries (itinerary_id) ON DELETE CASCADE,
    conductor_id        UUID,
    estado              VARCHAR(12) NOT NULL,
    actualizado_en      TIMESTAMP WITH TIME ZONE NOT NULL,
    ultima_posicion_en  TIMESTAMP WITH TIME ZONE,
    ultima_lat          DOUBLE PRECISION,
    ultima_lng          DOUBLE PRECISION,
    CONSTRAINT ck_tracking_sessions_estado CHECK (estado IN ('ACTIVO', 'EN_PAUSA', 'SIN_SENAL', 'FINALIZADO'))
);

CREATE INDEX idx_tracking_sessions_abiertas ON tracking_sessions (estado) WHERE estado <> 'FINALIZADO';
