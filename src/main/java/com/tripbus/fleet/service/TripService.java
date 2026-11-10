package com.tripbus.fleet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.fleet.common.error.ApiException;
import com.tripbus.fleet.domain.FleetItinerary;
import com.tripbus.fleet.domain.TripEvent;
import com.tripbus.fleet.domain.TripEventType;
import com.tripbus.fleet.events.FleetEvents;
import com.tripbus.fleet.repository.FleetItineraryRepository;
import com.tripbus.fleet.domain.TrackingSession;
import com.tripbus.fleet.repository.TrackingSessionRepository;
import com.tripbus.fleet.repository.TripAssignmentRepository;
import com.tripbus.fleet.repository.TripEventRepository;
import com.tripbus.fleet.web.dto.EtaView;
import com.tripbus.fleet.web.dto.ItineraryTracking;
import com.tripbus.fleet.web.dto.PositionResponse;
import com.tripbus.fleet.web.dto.StopView;
import com.tripbus.fleet.web.dto.TripEventRequest;
import com.tripbus.fleet.web.dto.TripEventResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class TripService {

    static final String ORIGEN_USUARIO = "USUARIO";

    private final FleetItineraryRepository itineraryRepository;
    private final TripEventRepository eventRepository;
    private final TripAssignmentRepository assignmentRepository;
    private final TrackingSessionRepository sessionRepository;
    private final RouteStopsCache stopsCache;
    private final TelemetryService telemetryService;
    private final TrackingWindow trackingWindow;
    private final Outbox outbox;
    private final TripAlertService alertService;
    private final EtaStore etaStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TripService(FleetItineraryRepository itineraryRepository, TripEventRepository eventRepository,
                       TripAssignmentRepository assignmentRepository, TrackingSessionRepository sessionRepository,
                       RouteStopsCache stopsCache,
                       TelemetryService telemetryService, TrackingWindow trackingWindow,
                       Outbox outbox, TripAlertService alertService, EtaStore etaStore, ObjectMapper objectMapper,
                       Clock clock) {
        this.alertService = alertService;
        this.etaStore = etaStore;
        this.itineraryRepository = itineraryRepository;
        this.eventRepository = eventRepository;
        this.assignmentRepository = assignmentRepository;
        this.sessionRepository = sessionRepository;
        this.stopsCache = stopsCache;
        this.telemetryService = telemetryService;
        this.trackingWindow = trackingWindow;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * US-046: reportar una novedad. Un conductor/operador solo puede reportar sobre viajes
     * de su propia empresa (US-005) y un conductor, además, solo de viajes donde está asignado
     * (US-121). El evento va al outbox en la misma transacción.
     */
    @Transactional
    public TripEventResponse report(UUID itineraryId, TripEventRequest request, Reportante quien) {
        FleetItinerary itinerary = autorizar(itineraryId, quien);
        TripEventResponse event = record(itinerary, request.tipo(), request.descripcion(), request.detalle(),
                quien.userId(), ORIGEN_USUARIO);
        // US-176: AVERIA, ACCIDENTE y VIA_CERRADA crean además una alerta para el operador.
        if (request.tipo().generaAlerta()) {
            alertService.raise(itinerary, request.tipo(), request.lat(), request.lng(), request.descripcion(),
                    request.puedeContinuar(), quien.userId());
        }
        // El operador decide en catalog si reprograma o cancela: aquí solo se avisa.
        if (Boolean.FALSE.equals(request.puedeContinuar())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("itineraryId", itinerary.getItineraryId());
            payload.put("companyId", itinerary.getCompanyId());
            payload.put("tipo", request.tipo().name());
            outbox.add(FleetEvents.TRIP_INTERRUPTED, payload);
        }
        return event;
    }

    /** Quien reporta una novedad (del JWT): usuario, si es ADMIN o CONDUCTOR, y su empresa. */
    public record Reportante(UUID userId, boolean admin, boolean conductor, UUID companyId) {
    }

    /**
     * El viaje existe, es de la empresa de quien reporta (salvo ADMIN) y, si es CONDUCTOR, está asignado.
     * Lo usa también el SOS de POST /trips/{id}/events.
     */
    @Transactional(readOnly = true)
    public FleetItinerary autorizarReporte(UUID itineraryId, Reportante quien) {
        return autorizar(itineraryId, quien);
    }

    private FleetItinerary autorizar(UUID itineraryId, Reportante quien) {
        FleetItinerary itinerary = itineraryRepository.findById(itineraryId)
                .orElseThrow(TripService::tripNotFound);
        if (!quien.admin() && !Objects.equals(quien.companyId(), itinerary.getCompanyId())) {
            throw ApiException.forbidden("NOT_YOUR_COMPANY", "Solo puede reportar novedades de viajes de su empresa");
        }
        if (quien.conductor() && !assignmentRepository.estaAsignado(itineraryId, quien.userId())) {
            throw DriverAssignmentService.notAssigned();
        }
        return itinerary;
    }

    /** Registra el evento + outbox. Requiere transacción. */
    public TripEventResponse record(FleetItinerary itinerary, TripEventType tipo, String descripcion,
                             Map<String, Object> detalle, UUID userId, String origen) {
        Instant now = clock.instant();
        String detalleJson = toJson(detalle);
        TripEvent event = eventRepository.save(new TripEvent(itinerary.getItineraryId(), tipo, descripcion,
                detalleJson, userId, origen, now));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripEventId", event.getId());
        payload.put("itineraryId", itinerary.getItineraryId());
        payload.put("busId", itinerary.getBusId());
        payload.put("tipo", tipo.name());
        payload.put("descripcion", descripcion);
        payload.put("payload", detalle);
        payload.put("timestamp", now);
        outbox.add(FleetEvents.TRIP_EVENT_REPORTED, payload);
        return toResponse(event);
    }

    @Transactional(readOnly = true)
    public List<TripEventResponse> timeline(UUID itineraryId) {
        return eventRepository.findByItineraryIdOrderByOcurridoEnAsc(itineraryId).stream().map(this::toResponse).toList();
    }

    /** US-039: posición del bus de un itinerario + ruta para dibujar el mapa. */
    @Transactional
    public ItineraryTracking tracking(UUID itineraryId) {
        FleetItinerary itinerary = itineraryRepository.findById(itineraryId)
                .orElseThrow(TripService::tripNotFound);
        Instant now = clock.instant();
        PositionResponse position = visiblePosition(itinerary, now);
        String estado = estado(itinerary, position, now);
        List<StopView> paradas = stopsCache.stops(itinerary.getRouteId()).stream()
                .map(s -> new StopView(s.getOrden(), s.getNombre(), s.getLat(), s.getLng(),
                        itinerary.getFechaSalida().plusSeconds(s.getMinutosDesdeSalida() * 60L)))
                .toList();
        // US-174: estado de la sesión de rastreo (null si nunca se encendió).
        TrackingSession session = sessionRepository.findById(itineraryId).orElse(null);
        return new ItineraryTracking(itineraryId, itinerary.getBusId(), itinerary.getOrigen(), itinerary.getDestino(),
                itinerary.getFechaSalida(), itinerary.getFechaLlegada(), estado, position, paradas,
                session != null ? session.getEstado().name() : null,
                session != null ? session.getUltimaPosicionEn() : null,
                etaVigente(itinerary, now).orElse(null));
    }

    /** US-180: GET /itineraries/{id}/eta. Vacío fuera de la ventana, cancelado, finalizado o sin posiciones aún. */
    @Transactional(readOnly = true)
    public Optional<EtaView> eta(UUID itineraryId) {
        FleetItinerary itinerary = itineraryRepository.findById(itineraryId)
                .orElseThrow(TripService::tripNotFound);
        return etaVigente(itinerary, clock.instant());
    }

    private Optional<EtaView> etaVigente(FleetItinerary itinerary, Instant now) {
        if (itinerary.isFinalizado() || !trackingWindow.isActive(itinerary, now)) {
            return Optional.empty();
        }
        return etaStore.find(itinerary.getItineraryId());
    }

    /** US-177: ¿ya hay LLEGADA_PARADA de esa parada? (el simulador no la duplica). */
    public boolean existeLlegada(UUID itineraryId, int orden) {
        return eventRepository.existsLlegada(itineraryId, orden);
    }

    /** Estado del viaje en ese instante (el mismo de bus-position): lo usa también US-170. */
    public String estado(FleetItinerary itinerary, Instant now) {
        return estado(itinerary, visiblePosition(itinerary, now), now);
    }

    /** US-039/US-047: la posición solo se muestra durante la ventana activa del viaje. */
    private PositionResponse visiblePosition(FleetItinerary itinerary, Instant now) {
        return trackingWindow.isActive(itinerary, now)
                ? telemetryService.lastByItinerary(itinerary.getItineraryId())
                        .filter(p -> !p.timestamp().isBefore(trackingWindow.start(itinerary)))
                        .orElse(null)
                : null;
    }

    /**
     * US-171: si el conductor marcó inicio o fin, mandan esos datos (EN_RUTA tras el inicio y
     * FINALIZADO tras el fin, aunque no haya llegado la hora). Sin ellos (p. ej. con el simulador),
     * el estado se calcula con el horario y la última posición.
     */
    static String estado(FleetItinerary itinerary, PositionResponse position, Instant now) {
        if (itinerary.isCancelado()) {
            return "CANCELADO";
        } else if (itinerary.isFinalizado()) {
            return "FINALIZADO";
        } else if (itinerary.isIniciado()) {
            return "EN_RUTA";
        } else if (now.isAfter(itinerary.getFechaLlegada())) {
            return "FINALIZADO";
        } else if (now.isBefore(itinerary.getFechaSalida())) {
            return "PROGRAMADO";
        } else if (position == null || Duration.between(position.timestamp(), now).toMinutes() > 5) {
            return "SIN_SENAL";
        }
        return "EN_RUTA";
    }

    /** Última posición del bus, solo si está haciendo un viaje (itinerario en ventana activa). */
    @Transactional(readOnly = true)
    public PositionResponse busPosition(UUID busId) {
        FleetItinerary active = trackingWindow.activeForBus(busId, clock.instant())
                .orElseThrow(() -> ApiException.notFound("NO_ACTIVE_TRIP", "El bus no está haciendo ningún viaje"));
        return telemetryService.lastByBus(busId)
                .filter(p -> !p.timestamp().isBefore(trackingWindow.start(active)))
                .orElseThrow(() -> ApiException.notFound("POSITION_NOT_FOUND", "No hay posiciones para este bus"));
    }

    private static ApiException tripNotFound() {
        return ApiException.notFound("TRIP_NOT_FOUND", "El viaje no existe");
    }

    private TripEventResponse toResponse(TripEvent e) {
        return new TripEventResponse(e.getId(), e.getItineraryId(), e.getTipo().name(), e.getDescripcion(),
                readJson(e.getPayload()), e.getOrigen(), e.getOcurridoEn());
    }

    private String toJson(Map<String, Object> detalle) {
        try {
            return detalle == null ? null : objectMapper.writeValueAsString(detalle);
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_DETAIL", "El detalle de la novedad no es válido");
        }
    }

    private JsonNode readJson(String json) {
        try {
            return json == null ? null : objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
