package com.tripbus.fleet.repository;

import com.tripbus.fleet.domain.TripEvent;
import com.tripbus.fleet.domain.TripEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TripEventRepository extends JpaRepository<TripEvent, UUID> {

    List<TripEvent> findByItineraryIdOrderByOcurridoEnAsc(UUID itineraryId);

    /** US-171: ¿ya hay INICIO/FIN (p. ej. del simulador)? Para no duplicarlos. */
    boolean existsByItineraryIdAndTipo(UUID itineraryId, TripEventType tipo);

    /** US-177: ¿ya hay LLEGADA_PARADA de esa parada (automática o del simulador)? detalle {"orden": n}. */
    @Query(value = """
            select count(*) > 0 from trip_events
            where itinerary_id = :itineraryId and tipo = 'LLEGADA_PARADA'
              and payload is not null and cast(payload as jsonb) ->> 'orden' = cast(:orden as text)
            """, nativeQuery = true)
    boolean existsLlegada(@Param("itineraryId") UUID itineraryId, @Param("orden") int orden);
}
