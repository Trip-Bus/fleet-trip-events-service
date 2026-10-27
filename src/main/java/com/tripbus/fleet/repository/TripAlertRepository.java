package com.tripbus.fleet.repository;

import com.tripbus.fleet.domain.TripAlert;
import com.tripbus.fleet.domain.TripEventType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripAlertRepository extends JpaRepository<TripAlert, UUID> {

    Optional<TripAlert> findByIdAndItineraryId(UUID id, UUID itineraryId);

    List<TripAlert> findByItineraryIdOrderByCreadoEnDesc(UUID itineraryId);

    /** US-176: el SOS abierto del viaje (a lo sumo uno, lo garantiza uq_trip_alerts_sos_abierto). */
    Optional<TripAlert> findFirstByItineraryIdAndTipoAndEstado(UUID itineraryId, TripEventType tipo,
                                                               TripAlert.Estado estado);

    // GET /alerts: las de la empresa del operador (o todas para ADMIN), con o sin filtro de estado.
    List<TripAlert> findByCompanyIdOrderByCreadoEnDesc(UUID companyId, Pageable pageable);

    List<TripAlert> findByCompanyIdAndEstadoOrderByCreadoEnDesc(UUID companyId, TripAlert.Estado estado,
                                                                Pageable pageable);

    List<TripAlert> findAllByOrderByCreadoEnDesc(Pageable pageable);

    List<TripAlert> findByEstadoOrderByCreadoEnDesc(TripAlert.Estado estado, Pageable pageable);
}
