-- US-046: idempotencia opcional de POST /trips/{id}/events (header Idempotency-Key).
-- El mismo key en el mismo viaje devuelve la novedad ya creada en vez de duplicarla.
ALTER TABLE trip_events ADD COLUMN idempotency_key VARCHAR(100);

CREATE UNIQUE INDEX uq_trip_events_idempotency ON trip_events (itinerary_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
