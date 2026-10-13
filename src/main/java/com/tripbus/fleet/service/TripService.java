package com.tripbus.fleet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.fleet.common.error.ApiException;
import com.tripbus.fleet.domain.FleetItinerary;
import com.tripbus.fleet.domain.TripEvent;
import com.tripbus.fleet.domain.TripEventType;
import com.tripbus.fleet.events.FleetEvents;
import com.tripbus.fleet.repository.FleetItineraryRepository;
import com.tripbus.fleet.repository.TripEventRepository;
import com.tripbus.fleet.web.dto.ItineraryTracking;
import com.tripbus.fleet.web.dto.PositionResponse;
import com.tripbus.fleet.web.dto.StopView;
import com.tripbus.fleet.web.dto.TripEventResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TripService {

    static final int MAX_IDEMPOTENCY_KEY = 100;

    private final FleetItineraryRepository itineraryRepository;
    private final TripEventRepository eventRepository;
    private final RouteStopsCache stopsCache;
    private final TelemetryService telemetryService;
    private final TrackingWindow trackingWindow;
    private final Outbox outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TripService(FleetItineraryRepository itineraryRepository, TripEventRepository eventRepository,
                       RouteStopsCache stopsCache, TelemetryService telemetryService, TrackingWindow trackingWindow,
                       Outbox outbox, ObjectMapper objectMapper, Clock clock) {
        this.itineraryRepository = itineraryRepository;
        this.eventRepository = eventRepository;
        this.stopsCache = stopsCache;
        this.telemetryService = telemetryService;
        this.trackingWindow = trackingWindow;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * US-046: reportar una novedad. Un conductor/operador solo puede reportar sobre viajes
     * de su propia empresa (US-005); si ni el token ni el viaje tienen empresa, solo un ADMIN.
     * Con Idempotency-Key, repetir la petición en el mismo viaje devuelve la novedad ya creada.
     * El evento va al outbox en la misma transacción.
     */
    @Transactional
    public TripEventResponse report(UUID itineraryId, TripEventType tipo, String descripcion,
                                    Map<String, Object> detalle, UUID userId, boolean isAdmin, UUID companyId,
                                    String origen, String idempotencyKey) {
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY)) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key debe tener entre 1 y " + MAX_IDEMPOTENCY_KEY + " caracteres");
        }
        // Bloquea el viaje: dos novedades simultáneas no pueden saltarse el orden ni el Idempotency-Key.
        FleetItinerary itinerary = itineraryRepository.findForUpdate(itineraryId)
                .orElseThrow(() -> ApiException.notFound("TRIP_NOT_FOUND", "El viaje no existe"));
        if (!isAdmin && (companyId == null || !companyId.equals(itinerary.getCompanyId()))) {
            throw ApiException.forbidden("NOT_YOUR_COMPANY", "Solo puede reportar novedades de viajes de su empresa");
        }
        if (idempotencyKey != null) {
            var previa = eventRepository.findByItineraryIdAndIdempotencyKey(itineraryId, idempotencyKey);
            if (previa.isPresent()) {
                return toResponse(previa.get());
            }
        }
        return record(itinerary, tipo, descripcion, detalle, userId, origen, idempotencyKey);
    }

    /**
     * Orden de las novedades del viaje: INICIO una sola vez; LLEGADA_PARADA, RETRASO y DESVIO entre
     * INICIO y FIN; FIN después de INICIO y una sola vez.
     */
    public boolean permits(UUID itineraryId, TripEventType tipo) {
        boolean iniciado = eventRepository.existsByItineraryIdAndTipo(itineraryId, TripEventType.INICIO);
        if (tipo == TripEventType.INICIO) {
            return !iniciado;
        }
        return iniciado && !eventRepository.existsByItineraryIdAndTipo(itineraryId, TripEventType.FIN);
    }

    /** Registra el evento + outbox (409 INVALID_TRIP_EVENT si rompe el orden). Requiere transacción. */
    public TripEventResponse record(FleetItinerary itinerary, TripEventType tipo, String descripcion,
                             Map<String, Object> detalle, UUID userId, String origen) {
        return record(itinerary, tipo, descripcion, detalle, userId, origen, null);
    }

    private TripEventResponse record(FleetItinerary itinerary, TripEventType tipo, String descripcion,
                                     Map<String, Object> detalle, UUID userId, String origen, String idempotencyKey) {
        if (!permits(itinerary.getItineraryId(), tipo)) {
            throw ApiException.conflict("INVALID_TRIP_EVENT", tipo == TripEventType.INICIO
                    ? "El viaje ya tiene INICIO"
                    : tipo + " solo se permite después de INICIO y antes de FIN");
        }
        Instant now = clock.instant();
        String detalleJson = toJson(detalle);
        TripEvent event = eventRepository.save(new TripEvent(itinerary.getItineraryId(), tipo, descripcion,
                detalleJson, userId, origen, now, idempotencyKey));

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
                .orElseThrow(() -> ApiException.notFound("TRIP_NOT_FOUND", "El viaje no existe"));
        Instant now = clock.instant();
        // US-039/US-047: la posición solo se muestra durante la ventana activa del viaje.
        PositionResponse position = trackingWindow.isActive(itinerary, now)
                ? telemetryService.lastByItinerary(itineraryId)
                        .filter(p -> !p.timestamp().isBefore(trackingWindow.start(itinerary)))
                        .orElse(null)
                : null;
        String estado;
        if (itinerary.isCancelado()) {
            estado = "CANCELADO";
        } else if (now.isAfter(itinerary.getFechaLlegada())) {
            estado = "FINALIZADO";
        } else if (now.isBefore(itinerary.getFechaSalida())) {
            estado = "PROGRAMADO";
        } else if (position == null || Duration.between(position.timestamp(), now).toMinutes() > 5) {
            estado = "SIN_SENAL";
        } else {
            estado = "EN_RUTA";
        }
        List<StopView> paradas = stopsCache.stops(itinerary.getRouteId()).stream()
                .map(s -> new StopView(s.getOrden(), s.getNombre(), s.getLat(), s.getLng(),
                        itinerary.getFechaSalida().plusSeconds(s.getMinutosDesdeSalida() * 60L)))
                .toList();
        return new ItineraryTracking(itineraryId, itinerary.getBusId(), itinerary.getOrigen(), itinerary.getDestino(),
                itinerary.getFechaSalida(), itinerary.getFechaLlegada(), estado, position, paradas);
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
