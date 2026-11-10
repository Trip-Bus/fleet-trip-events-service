package com.tripbus.fleet.repository;

import com.tripbus.fleet.domain.FleetItinerary;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FleetItineraryRepository extends JpaRepository<FleetItinerary, UUID> {

    /** Viajes en curso (o que terminaron hace poco y aún no se anunció su FIN en la simulación). */
    @Query("""
            select f from FleetItinerary f
            where f.cancelado = false and f.fechaSalida <= :now and f.fechaLlegada > :desde
            order by f.fechaSalida
            """)
    List<FleetItinerary> findEnCurso(@Param("now") Instant now, @Param("desde") Instant desde, Pageable pageable);

    /** US-121: bloquea la fila del viaje (dos asignaciones del mismo viaje se hacen una tras otra). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FleetItinerary f where f.itineraryId = :id")
    Optional<FleetItinerary> findByIdForUpdate(@Param("id") UUID id);

    /** Viajes no cancelados del bus cuya ventana de seguimiento contiene el instante consultado. */
    @Query("""
            select f from FleetItinerary f
            where f.busId = :busId and f.cancelado = false
              and f.fechaSalida <= :salidaHasta and f.fechaLlegada >= :llegadaDesde
            order by f.fechaSalida
            """)
    List<FleetItinerary> findEnVentana(@Param("busId") UUID busId, @Param("salidaHasta") Instant salidaHasta,
                                       @Param("llegadaDesde") Instant llegadaDesde);

    /** US-178: viajes no cancelados de la empresa cuya ventana de rastreo contiene el instante consultado. */
    @Query("""
            select f from FleetItinerary f
            where f.companyId = :companyId and f.cancelado = false
              and f.fechaSalida <= :salidaHasta and f.fechaLlegada >= :llegadaDesde
            order by f.fechaSalida
            """)
    List<FleetItinerary> findEnVentanaDeEmpresa(@Param("companyId") UUID companyId,
                                                @Param("salidaHasta") Instant salidaHasta,
                                                @Param("llegadaDesde") Instant llegadaDesde, Pageable pageable);

    /** US-178: lo mismo para todas las empresas (ADMIN). */
    @Query("""
            select f from FleetItinerary f
            where f.cancelado = false and f.fechaSalida <= :salidaHasta and f.fechaLlegada >= :llegadaDesde
            order by f.fechaSalida
            """)
    List<FleetItinerary> findEnVentanaTodas(@Param("salidaHasta") Instant salidaHasta,
                                            @Param("llegadaDesde") Instant llegadaDesde, Pageable pageable);
}
