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
import java.util.Objects;
import java.util.UUID;

@Service
public class TripService {

    private final FleetItineraryRepository itineraryRepository;
    private final TripEventRepository eventRepository;
    private final RouteStopsCache stopsCache;
    private final TelemetryService telemetryService;
    private final Outbox outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TripService(FleetItineraryRepository itineraryRepository, TripEventRepository eventRepository,
                       RouteStopsCache stopsCache, TelemetryService telemetryService, Outbox outbox,
                       ObjectMapper objectMapper, Clock clock) {
        this.itineraryRepository = itineraryRepository;
        this.eventRepository = eventRepository;
        this.stopsCache = stopsCache;
        this.telemetryService = telemetryService;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * US-046: reportar una novedad. Un conductor/operador solo puede reportar sobre viajes
     * de su propia empresa (US-005). El evento va al outbox en la misma transacción.
     */
    @Transactional
    public TripEventResponse report(UUID itineraryId, TripEventType tipo, String descripcion,
                                    Map<String, Object> detalle, UUID userId, boolean isAdmin, UUID companyId,
                                    String origen) {
        FleetItinerary itinerary = itineraryRepository.findById(itineraryId)
                .orElseThrow(() -> ApiException.notFound("TRIP_NOT_FOUND", "El viaje no existe"));
        if (!isAdmin && userId != null && !Objects.equals(companyId, itinerary.getCompanyId())) {
            throw ApiException.forbidden("NOT_YOUR_COMPANY", "Solo puede reportar novedades de viajes de su empresa");
        }
        return record(itinerary, tipo, descripcion, detalle, userId, origen);
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
                .orElseThrow(() -> ApiException.notFound("TRIP_NOT_FOUND", "El viaje no existe"));
        PositionResponse position = telemetryService.lastByItinerary(itineraryId).orElse(null);
        Instant now = clock.instant();
        String estado;
        if (itinerary.isCancelado()) {
            estado = "CANCELADO";
        } else if (now.isBefore(itinerary.getFechaSalida())) {
            estado = "PROGRAMADO";
        } else if (now.isAfter(itinerary.getFechaLlegada()) && position == null) {
            estado = "FINALIZADO";
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
