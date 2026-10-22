-- US-121: conductores asignados a cada viaje. Máximo un PRINCIPAL y un RELEVO por viaje.
-- Que un conductor no tenga dos viajes cruzados lo garantiza el servicio (pg_advisory_xact_lock
-- por conductor + verificación de las ventanas de rastreo dentro de la misma transacción).
CREATE TABLE trip_assignments (
    id            UUID PRIMARY KEY,
    itinerary_id  UUID NOT NULL REFERENCES fleet_itineraries (itinerary_id) ON DELETE CASCADE,
    conductor_id  UUID NOT NULL,
    rol           VARCHAR(10) NOT NULL,
    asignado_por  UUID NOT NULL,
    asignado_en   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_trip_assignments_rol UNIQUE (itinerary_id, rol),
    CONSTRAINT ck_trip_assignments_rol CHECK (rol IN ('PRINCIPAL', 'RELEVO'))
);

CREATE INDEX idx_trip_assignments_conductor ON trip_assignments (conductor_id);
