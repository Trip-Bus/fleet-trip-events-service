-- US-182: puntualidad de cada viaje terminado (se llena al finalizar el viaje o cuando su rastreo pasa a
-- FINALIZADO). Queda aunque las posiciones se borren (HT-08) y es la base del reporte de US-193.
CREATE TABLE trip_punctuality (
    itinerary_id             UUID PRIMARY KEY REFERENCES fleet_itineraries (itinerary_id) ON DELETE CASCADE,
    salida_programada        TIMESTAMP WITH TIME ZONE NOT NULL,
    salida_real              TIMESTAMP WITH TIME ZONE,
    llegada_programada       TIMESTAMP WITH TIME ZONE NOT NULL,
    llegada_real             TIMESTAMP WITH TIME ZONE,
    minutos_retraso_salida   INTEGER,
    minutos_retraso_llegada  INTEGER,
    paradas_a_tiempo         INTEGER NOT NULL,
    paradas_total            INTEGER NOT NULL,
    conductor_id             UUID,
    route_id                 UUID NOT NULL,
    company_id               UUID,
    calculado_en             TIMESTAMP WITH TIME ZONE NOT NULL
);

-- US-193: el reporte filtra por empresa y fecha de salida y agrupa por ruta o conductor.
CREATE INDEX idx_trip_punctuality_empresa ON trip_punctuality (company_id, salida_programada);

-- HT-08: la limpieza de posiciones recorre bus_positions por fecha.
CREATE INDEX idx_bus_positions_registrado ON bus_positions (registrado_en);
