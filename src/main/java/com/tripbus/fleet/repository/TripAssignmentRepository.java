package com.tripbus.fleet.repository;

import com.tripbus.fleet.domain.DriverRole;
import com.tripbus.fleet.domain.TripAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripAssignmentRepository extends JpaRepository<TripAssignment, UUID> {

    @Query("select a from TripAssignment a where a.itinerary.itineraryId = :itineraryId and a.rol = :rol")
    Optional<TripAssignment> findByViajeYRol(@Param("itineraryId") UUID itineraryId, @Param("rol") DriverRole rol);

    @Query("select a from TripAssignment a where a.itinerary.itineraryId = :itineraryId order by a.rol")
    List<TripAssignment> findByViaje(@Param("itineraryId") UUID itineraryId);

    /** US-178: el conductor de ese rol de un bloque de viajes (una sola consulta para todo el mapa). */
    @Query("select a from TripAssignment a where a.itinerary.itineraryId in :itineraryIds and a.rol = :rol")
    List<TripAssignment> findByViajesYRol(@Param("itineraryIds") Collection<UUID> itineraryIds,
                                          @Param("rol") DriverRole rol);

    @Query("""
            select count(a) > 0 from TripAssignment a
            where a.itinerary.itineraryId = :itineraryId and a.conductorId = :conductorId
            """)
    boolean estaAsignado(@Param("itineraryId") UUID itineraryId, @Param("conductorId") UUID conductorId);

    /**
     * Asignaciones del conductor en viajes no cancelados con fechaSalida <= salidaHasta y
     * fechaLlegada >= llegadaDesde. Con los límites que calcula el servicio sirve para buscar
     * cruces de ventanas y el viaje en ventana en un instante.
     */
    @Query("""
            select a from TripAssignment a join fetch a.itinerary f
            where a.conductorId = :conductorId and f.cancelado = false
              and f.fechaSalida <= :salidaHasta and f.fechaLlegada >= :llegadaDesde
            order by f.fechaSalida
            """)
    List<TripAssignment> findDelConductorEnVentana(@Param("conductorId") UUID conductorId,
                                                   @Param("salidaHasta") Instant salidaHasta,
                                                   @Param("llegadaDesde") Instant llegadaDesde);

    /** US-170: viajes del conductor que salen en [desde, hasta). */
    @Query("""
            select a from TripAssignment a join fetch a.itinerary f
            where a.conductorId = :conductorId and f.fechaSalida >= :desde and f.fechaSalida < :hasta
            order by f.fechaSalida
            """)
    List<TripAssignment> findDelConductorQueSalenEntre(@Param("conductorId") UUID conductorId,
                                                       @Param("desde") Instant desde, @Param("hasta") Instant hasta);
}
