package com.tripbus.fleet.repository;

import com.tripbus.fleet.domain.RouteStop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RouteStopRepository extends JpaRepository<RouteStop, RouteStop.Key> {

    List<RouteStop> findByRouteIdOrderByOrdenAsc(UUID routeId);

    boolean existsByRouteId(UUID routeId);
}
