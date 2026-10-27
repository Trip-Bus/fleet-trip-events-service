-- US-177: avance real del bus según su posición: orden de la última parada a la que llegó
-- (0 = ninguna). Es independiente de sim_ultima_parada (el horario que sigue el simulador).
ALTER TABLE fleet_itineraries
    ADD COLUMN ultima_parada_real INTEGER NOT NULL DEFAULT 0;

-- US-177: el desvío detectado automáticamente crea una alerta (gravedad ALTA).
ALTER TABLE trip_alerts DROP CONSTRAINT ck_trip_alerts_tipo;
ALTER TABLE trip_alerts ADD CONSTRAINT ck_trip_alerts_tipo
    CHECK (tipo IN ('AVERIA', 'ACCIDENTE', 'VIA_CERRADA', 'SOS', 'DESVIO'));

-- Una sola alerta de desvío abierta a la vez por viaje.
CREATE UNIQUE INDEX uq_trip_alerts_desvio_abierto ON trip_alerts (itinerary_id)
    WHERE tipo = 'DESVIO' AND estado = 'ABIERTA';
