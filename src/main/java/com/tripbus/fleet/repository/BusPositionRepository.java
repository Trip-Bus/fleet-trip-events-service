package com.tripbus.fleet.repository;

import com.tripbus.fleet.domain.BusPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface BusPositionRepository extends JpaRepository<BusPosition, Long> {

    Optional<BusPosition> findFirstByBusIdOrderByRegistradoEnDesc(UUID busId);

    Optional<BusPosition> findFirstByItineraryIdOrderByRegistradoEnDesc(UUID itineraryId);

    boolean existsByEventId(String eventId);

    @Modifying
    @Query("delete from BusPosition p where p.registradoEn < :antes")
    int deleteOlderThan(@Param("antes") Instant antes);
}
