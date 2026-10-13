package com.tripbus.fleet.repository;

import com.tripbus.fleet.domain.TripEvent;
import com.tripbus.fleet.domain.TripEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripEventRepository extends JpaRepository<TripEvent, UUID> {

    List<TripEvent> findByItineraryIdOrderByOcurridoEnAsc(UUID itineraryId);

    boolean existsByItineraryIdAndTipo(UUID itineraryId, TripEventType tipo);

    Optional<TripEvent> findByItineraryIdAndIdempotencyKey(UUID itineraryId, String idempotencyKey);
}
