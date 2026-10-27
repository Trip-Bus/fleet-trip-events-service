package com.tripbus.fleet.repository;

import com.tripbus.fleet.domain.TripChecklist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TripChecklistRepository extends JpaRepository<TripChecklist, UUID> {
}
