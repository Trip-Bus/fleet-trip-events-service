package com.tripbus.fleet.repository;

import com.tripbus.fleet.domain.TripEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TripEventRepository extends JpaRepository<TripEvent, UUID> {

    List<TripEvent> findByItineraryIdOrderByOcurridoEnAsc(UUID itineraryId);
}
