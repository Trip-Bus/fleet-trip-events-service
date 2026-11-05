package com.tripbus.fleet.repository;

import com.tripbus.fleet.domain.BusPosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BusPositionRepository extends JpaRepository<BusPosition, Long> {

    Optional<BusPosition> findFirstByBusIdOrderByRegistradoEnDesc(UUID busId);

    Optional<BusPosition> findFirstByItineraryIdOrderByRegistradoEnDesc(UUID itineraryId);

    boolean existsByEventId(String eventId);
}
