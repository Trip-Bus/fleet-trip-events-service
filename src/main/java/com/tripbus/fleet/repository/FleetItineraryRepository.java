package com.tripbus.fleet.repository;

import com.tripbus.fleet.domain.FleetItinerary;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface FleetItineraryRepository extends JpaRepository<FleetItinerary, UUID> {

    /** Viajes en curso (o que terminaron hace poco y aún no se anunció su FIN en la simulación). */
    @Query("""
            select f from FleetItinerary f
            where f.cancelado = false and f.fechaSalida <= :now and f.fechaLlegada > :desde
            order by f.fechaSalida
            """)
    List<FleetItinerary> findEnCurso(@Param("now") Instant now, @Param("desde") Instant desde, Pageable pageable);
}
