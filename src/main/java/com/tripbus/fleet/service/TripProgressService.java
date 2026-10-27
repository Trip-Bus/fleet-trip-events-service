package com.tripbus.fleet.service;

import com.tripbus.fleet.common.error.ApiException;
import com.tripbus.fleet.config.FleetProperties;
import com.tripbus.fleet.domain.FleetItinerary;
import com.tripbus.fleet.domain.TrackingSession;
import com.tripbus.fleet.domain.TripChecklist;
import com.tripbus.fleet.domain.TripEventType;
import com.tripbus.fleet.repository.FleetItineraryRepository;
import com.tripbus.fleet.repository.TripAssignmentRepository;
import com.tripbus.fleet.repository.TripChecklistRepository;
import com.tripbus.fleet.repository.TripEventRepository;
import com.tripbus.fleet.web.dto.TripProgressResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * US-171: el conductor asignado inicia el viaje (dentro de la ventana y con el checklist completo) y
 * lo finaliza; el OPERADOR de la empresa o un ADMIN pueden forzar el fin. Se registran las novedades
 * INICIO y FIN (sin duplicar las del simulador) y el rastreo se enciende o se cierra.
 */
@Service
public class TripProgressService {



    private final FleetItineraryRepository itineraryRepository;
    private final TripAssignmentRepository assignmentRepository;
    private final TripChecklistRepository checklistRepository;
    private final TripEventRepository eventRepository;
    private final TripService tripService;
    private final DriverTrackingService trackingService;
    private final TrackingWindow trackingWindow;
    private final FleetProperties properties;
    private final Clock clock;

    public TripProgressService(FleetItineraryRepository itineraryRepository,
                               TripAssignmentRepository assignmentRepository,
                               TripChecklistRepository checklistRepository, TripEventRepository eventRepository,
                               TripService tripService, DriverTrackingService trackingService,
                               TrackingWindow trackingWindow, FleetProperties properties, Clock clock) {
        this.itineraryRepository = itineraryRepository;
        this.assignmentRepository = assignmentRepository;
        this.checklistRepository = checklistRepository;
        this.eventRepository = eventRepository;
        this.tripService = tripService;
        this.trackingService = trackingService;
        this.trackingWindow = trackingWindow;
        this.properties = properties;
        this.clock = clock;
    }

    /** POST /driver/trips/{itineraryId}/start. */
    @Transactional
    public TripProgressResponse start(UUID itineraryId, UUID conductorId, Map<String, Boolean> checklist,
                                      String observacion) {
        FleetItinerary itinerary = viajeDelConductor(itineraryId, conductorId);
        Instant now = clock.instant();
        if (itinerary.isCancelado()) {
            throw ApiException.conflict("TRIP_CANCELLED", "El viaje está cancelado");
        }
        if (!trackingWindow.isActive(itinerary, now)) {
            throw ApiException.conflict("OUTSIDE_TRACKING_WINDOW", "El viaje no está dentro de su ventana de rastreo");
        }
        if (itinerary.isIniciado()) {
            throw ApiException.conflict("TRIP_ALREADY_STARTED", "El viaje ya fue iniciado");
        }
        List<String> faltantes = properties.checklistItems().stream()
                .filter(item -> !Boolean.TRUE.equals(checklist.get(item)))
                .toList();
        if (!faltantes.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CHECKLIST_INCOMPLETE",
                    "Faltan ítems obligatorios del checklist", Map.of("faltantes", faltantes));
        }

        itinerary.iniciar(conductorId, now);
        Map<String, Boolean> items = new LinkedHashMap<>();
        properties.checklistItems().forEach(item -> items.put(item, true));
        checklistRepository.save(new TripChecklist(itineraryId, conductorId, items, observacion, now));
        registrarSiNoExiste(itinerary, TripEventType.INICIO, "El conductor inició el viaje", conductorId);
        return toResponse(itinerary, trackingService.encender(itinerary, conductorId));
    }

    /** POST /driver/trips/{itineraryId}/finish: el conductor asignado. */
    @Transactional
    public TripProgressResponse finishByDriver(UUID itineraryId, UUID conductorId) {
        return finish(viajeDelConductor(itineraryId, conductorId), conductorId);
    }

    /** POST /trips/{itineraryId}/finish: fin forzado por el OPERADOR de la empresa o un ADMIN. */
    @Transactional
    public TripProgressResponse finishByOperator(UUID itineraryId, UUID userId, boolean isAdmin, UUID companyId) {
        FleetItinerary itinerary = itineraryRepository.findByIdForUpdate(itineraryId)
                .filter(it -> isAdmin || (companyId != null && companyId.equals(it.getCompanyId())))
                .orElseThrow(TripProgressService::tripNotFound);
        return finish(itinerary, userId);
    }

    private TripProgressResponse finish(FleetItinerary itinerary, UUID userId) {
        if (!itinerary.isIniciado()) {
            throw ApiException.conflict("TRIP_NOT_STARTED", "El viaje no ha sido iniciado");
        }
        if (itinerary.isFinalizado()) {
            throw ApiException.conflict("TRIP_ALREADY_FINISHED", "El viaje ya fue finalizado");
        }
        itinerary.finalizar(clock.instant());
        registrarSiNoExiste(itinerary, TripEventType.FIN, "El viaje finalizó", userId);
        return toResponse(itinerary, trackingService.finalizar(itinerary));
    }

    /** Bloquea el viaje (dos inicios simultáneos no pasan los dos) y exige que el conductor esté asignado. */
    private FleetItinerary viajeDelConductor(UUID itineraryId, UUID conductorId) {
        FleetItinerary itinerary = itineraryRepository.findByIdForUpdate(itineraryId)
                .orElseThrow(TripProgressService::tripNotFound);
        if (!assignmentRepository.estaAsignado(itineraryId, conductorId)) {
            throw DriverAssignmentService.notAssigned();
        }
        return itinerary;
    }

    /** Si el simulador ya generó la novedad, no se duplica. */
    private void registrarSiNoExiste(FleetItinerary itinerary, TripEventType tipo, String descripcion, UUID userId) {
        if (!eventRepository.existsByItineraryIdAndTipo(itinerary.getItineraryId(), tipo)) {
            tripService.record(itinerary, tipo, descripcion, null, userId, TripService.ORIGEN_USUARIO);
        }
    }

    private static ApiException tripNotFound() {
        return ApiException.notFound("TRIP_NOT_FOUND", "El viaje no existe");
    }

    private static TripProgressResponse toResponse(FleetItinerary itinerary, TrackingSession session) {
        return new TripProgressResponse(itinerary.getItineraryId(), itinerary.getIniciadoEn(),
                itinerary.getFinalizadoEn(), session.getEstado().name());
    }
}
