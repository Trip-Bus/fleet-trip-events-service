package com.tripbus.fleet.repository;

import com.tripbus.fleet.domain.TripEvent;
import com.tripbus.fleet.domain.TripEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TripEventRepository extends JpaRepository<TripEvent, UUID> {

    List<TripEvent> findByItineraryIdOrderByOcurridoEnAsc(UUID itineraryId);

    /** US-171: ¿ya hay INICIO/FIN (p. ej. del simulador)? Para no duplicarlos. */
    boolean existsByItineraryIdAndTipo(UUID itineraryId, TripEventType tipo);
}
