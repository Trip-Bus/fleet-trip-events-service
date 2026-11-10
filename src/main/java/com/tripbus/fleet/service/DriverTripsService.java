package com.tripbus.fleet.service;

import com.tripbus.fleet.common.error.ApiException;
import com.tripbus.fleet.domain.FleetItinerary;
import com.tripbus.fleet.domain.TripAssignment;
import com.tripbus.fleet.repository.TripAssignmentRepository;
import com.tripbus.fleet.web.dto.DriverTripView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** US-170: los viajes asignados al conductor (los de un día y el que está ahora en su ventana). */
@Service
public class DriverTripsService {

    /** El "día" del conductor es el día calendario en Colombia. */
    static final ZoneId ZONA = ZoneId.of("America/Bogota");

    private final TripAssignmentRepository assignmentRepository;
    private final TrackingWindow trackingWindow;
    private final TripService tripService;
    private final Clock clock;

    public DriverTripsService(TripAssignmentRepository assignmentRepository, TrackingWindow trackingWindow,
                              TripService tripService, Clock clock) {
        this.assignmentRepository = assignmentRepository;
        this.trackingWindow = trackingWindow;
        this.tripService = tripService;
        this.clock = clock;
    }

    /** Viajes que salen ese día (por defecto hoy en America/Bogota), ordenados por salida. */
    @Transactional(readOnly = true)
    public List<DriverTripView> trips(UUID conductorId, LocalDate fecha) {
        LocalDate dia = fecha != null ? fecha : LocalDate.now(clock.withZone(ZONA));
        Instant now = clock.instant();
        return assignmentRepository.findDelConductorQueSalenEntre(conductorId,
                        dia.atStartOfDay(ZONA).toInstant(), dia.plusDays(1).atStartOfDay(ZONA).toInstant())
                .stream().map(a -> toView(a, now)).toList();
    }

    /** El viaje asignado que está ahora en su ventana (el de salida más temprana si hay varios). */
    @Transactional(readOnly = true)
    public DriverTripView active(UUID conductorId) {
        Instant now = clock.instant();
        return assignmentRepository.findDelConductorEnVentana(conductorId,
                        now.plus(trackingWindow.before()), now.minus(trackingWindow.after()))
                .stream().findFirst()
                .map(a -> toView(a, now))
                .orElseThrow(() -> ApiException.notFound("NO_ACTIVE_TRIP",
                        "No tiene ningún viaje asignado en curso"));
    }

    private DriverTripView toView(TripAssignment a, Instant now) {
        FleetItinerary it = a.getItinerary();
        return new DriverTripView(it.getItineraryId(), it.getBusId(), it.getOrigen(), it.getDestino(),
                it.getFechaSalida(), it.getFechaLlegada(), a.getRol().name(), tripService.estado(it, now),
                trackingWindow.start(it), trackingWindow.end(it), trackingWindow.isActive(it, now),
                it.isIniciado(), it.isFinalizado());
    }
}
