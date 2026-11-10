package com.tripbus.fleet.repository;

import com.tripbus.fleet.domain.RouteStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RouteStopRepository extends JpaRepository<RouteStop, RouteStop.Key> {

    List<RouteStop> findByRouteIdOrderByOrdenAsc(UUID routeId);

    boolean existsByRouteId(UUID routeId);

    /** HT-16: borra (de inmediato, sin esperar al flush) las paradas de la ruta antes de reemplazarlas. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RouteStop s where s.routeId = :routeId")
    int deleteByRouteId(@Param("routeId") UUID routeId);
}
