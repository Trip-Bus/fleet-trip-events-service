package com.tripbus.fleet.service;

import com.tripbus.fleet.common.error.ApiException;
import com.tripbus.fleet.domain.FleetItinerary;
import com.tripbus.fleet.domain.TripAlert;
import com.tripbus.fleet.domain.TripEventType;
import com.tripbus.fleet.events.FleetEvents;
import com.tripbus.fleet.repository.FleetItineraryRepository;
import com.tripbus.fleet.repository.TripAlertRepository;
import com.tripbus.fleet.web.dto.PositionResponse;
import com.tripbus.fleet.web.dto.TripAlertResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * US-176: alertas de novedades graves (AVERIA, ACCIDENTE, VIA_CERRADA) y del botón SOS. Cada alerta
 * publica TripAlertRaised por el outbox (tópico privado de la empresa, nunca la línea de tiempo
 * pública). El operador de la empresa (o un ADMIN) las consulta y las resuelve (TripAlertResolved).
 */
@Service
public class TripAlertService {

    /** Máximo de alertas que devuelve GET /alerts (las más recientes). */
    static final Pageable MAX_ALERTAS = PageRequest.of(0, 200);

    /** Resultado del SOS: la alerta y si se creó ahora (false: ya había una abierta). */
    public record SosResult(TripAlertResponse alerta, boolean nueva) {
    }

    private final TripAlertRepository alertRepository;
    private final FleetItineraryRepository itineraryRepository;
    private final DriverTrackingService trackingService;
    private final TelemetryService telemetryService;
    private final Outbox outbox;
    private final Clock clock;

    public TripAlertService(TripAlertRepository alertRepository, FleetItineraryRepository itineraryRepository,
                            DriverTrackingService trackingService, TelemetryService telemetryService,
                            Outbox outbox, Clock clock) {
        this.alertRepository = alertRepository;
        this.itineraryRepository = itineraryRepository;
        this.trackingService = trackingService;
        this.telemetryService = telemetryService;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Crea la alerta de una novedad grave y publica TripAlertRaised. Requiere transacción. */
    @Transactional(propagation = Propagation.MANDATORY)
    public TripAlertResponse raise(FleetItinerary itinerary, TripEventType tipo, Double lat, Double lng,
                                   String mensaje, Boolean puedeContinuar, UUID userId) {
        return crearAlerta(itinerary, tipo, lat, lng, mensaje, puedeContinuar, userId);
    }

    private TripAlertResponse crearAlerta(FleetItinerary itinerary, TripEventType tipo, Double lat, Double lng,
                                          String mensaje, Boolean puedeContinuar, UUID userId) {
        PositionResponse ultima = lat == null || lng == null ? ultimaPosicion(itinerary) : null;
        TripAlert alert = alertRepository.save(new TripAlert(itinerary, tipo,
                ultima != null ? Double.valueOf(ultima.lat()) : lat, ultima != null ? Double.valueOf(ultima.lng()) : lng,
                mensaje, puedeContinuar, userId, clock.instant()));
        outbox.add(FleetEvents.TRIP_ALERT_RAISED, raisedPayload(alert));
        return toResponse(alert);
    }

    /**
     * US-177: alerta de desvío detectado (gravedad ALTA), solo si el viaje no tiene ya una abierta.
     * Devuelve true si la creó. Requiere transacción (el viaje ya viene bloqueado por quien llama).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean raiseDesvio(FleetItinerary itinerary, double lat, double lng, String mensaje) {
        if (alertRepository.findFirstByItineraryIdAndTipoAndEstado(itinerary.getItineraryId(), TripEventType.DESVIO,
                TripAlert.Estado.ABIERTA).isPresent()) {
            return false;
        }
        TripAlert alert = alertRepository.save(new TripAlert(itinerary, TripEventType.DESVIO, lat, lng, mensaje, true,
                null, clock.instant()).conGravedad(TripEventType.Gravedad.ALTA));
        outbox.add(FleetEvents.TRIP_ALERT_RAISED, raisedPayload(alert));
        return true;
    }

    /** POST /driver/sos: resuelve el viaje activo del conductor (US-175: 409 si no tiene). */
    @Transactional
    public SosResult sos(UUID conductorId, Double lat, Double lng, String mensaje) {
        FleetItinerary itinerary = trackingService.viajeEnVentana(conductorId, clock.instant());
        return sosEnViaje(itinerary.getItineraryId(), lat, lng, mensaje, conductorId);
    }

    /** SOS de POST /trips/{id}/events (el viaje ya lo autorizó TripService.autorizarReporte). */
    @Transactional
    public SosResult sosDelViaje(UUID itineraryId, Double lat, Double lng, String mensaje, UUID userId) {
        return sosEnViaje(itineraryId, lat, lng, mensaje, userId);
    }

    /**
     * Un solo SOS abierto por viaje: si se repite antes de que lo resuelvan, devuelve el mismo. El
     * bloqueo del viaje serializa dos toques simultáneos (y uq_trip_alerts_sos_abierto lo garantiza).
     */
    private SosResult sosEnViaje(UUID itineraryId, Double lat, Double lng, String mensaje, UUID userId) {
        FleetItinerary itinerary = itineraryRepository.findByIdForUpdate(itineraryId)
                .orElseThrow(TripAlertService::tripNotFound);
        Optional<TripAlert> abierta = alertRepository.findFirstByItineraryIdAndTipoAndEstado(itineraryId,
                TripEventType.SOS, TripAlert.Estado.ABIERTA);
        if (abierta.isPresent()) {
            return new SosResult(toResponse(abierta.get()), false);
        }
        return new SosResult(crearAlerta(itinerary, TripEventType.SOS, lat, lng, mensaje, null, userId), true);
    }

    /** POST /trips/{id}/alerts/{alertId}/resolve. Otra empresa: 404 (no se revela que existe). */
    @Transactional
    public TripAlertResponse resolve(UUID itineraryId, UUID alertId, String nota, UUID userId, boolean isAdmin,
                                     UUID companyId) {
        TripAlert alert = alertRepository.findByIdAndItineraryId(alertId, itineraryId)
                .filter(a -> esDeSuEmpresa(a.getCompanyId(), isAdmin, companyId))
                .orElseThrow(() -> ApiException.notFound("ALERT_NOT_FOUND", "La alerta no existe"));
        if (alert.getEstado() == TripAlert.Estado.RESUELTA) {
            throw ApiException.conflict("ALERT_ALREADY_RESOLVED", "La alerta ya fue resuelta");
        }
        alert.resolver(userId, nota, clock.instant());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alertId", alert.getId());
        payload.put("itineraryId", alert.getItineraryId());
        payload.put("companyId", alert.getCompanyId());
        payload.put("busId", alert.getBusId());
        payload.put("tipo", alert.getTipo().name());
        payload.put("nota", nota);
        payload.put("resueltoEn", alert.getResueltoEn());
        outbox.add(FleetEvents.TRIP_ALERT_RESOLVED, payload);
        return toResponse(alert);
    }

    /** GET /trips/{id}/alerts: OPERADOR de la empresa o ADMIN. */
    @Transactional(readOnly = true)
    public List<TripAlertResponse> alertsOfTrip(UUID itineraryId, boolean isAdmin, UUID companyId) {
        itineraryRepository.findById(itineraryId)
                .filter(it -> esDeSuEmpresa(it.getCompanyId(), isAdmin, companyId))
                .orElseThrow(TripAlertService::tripNotFound);
        return alertRepository.findByItineraryIdOrderByCreadoEnDesc(itineraryId).stream()
                .map(TripAlertService::toResponse).toList();
    }

    /** GET /alerts?estado=: las de la empresa del operador (un ADMIN ve todas), las más recientes primero. */
    @Transactional(readOnly = true)
    public List<TripAlertResponse> alerts(TripAlert.Estado estado, boolean isAdmin, UUID companyId) {
        List<TripAlert> alerts;
        if (isAdmin) {
            alerts = estado == null ? alertRepository.findAllByOrderByCreadoEnDesc(MAX_ALERTAS)
                    : alertRepository.findByEstadoOrderByCreadoEnDesc(estado, MAX_ALERTAS);
        } else if (companyId == null) {
            alerts = List.of();
        } else {
            alerts = estado == null ? alertRepository.findByCompanyIdOrderByCreadoEnDesc(companyId, MAX_ALERTAS)
                    : alertRepository.findByCompanyIdAndEstadoOrderByCreadoEnDesc(companyId, estado, MAX_ALERTAS);
        }
        return alerts.stream().map(TripAlertService::toResponse).toList();
    }

    private PositionResponse ultimaPosicion(FleetItinerary itinerary) {
        return telemetryService.lastByItinerary(itinerary.getItineraryId()).orElse(null);
    }

    private static boolean esDeSuEmpresa(UUID companyDelViaje, boolean isAdmin, UUID companyId) {
        return isAdmin || (companyId != null && companyId.equals(companyDelViaje));
    }

    private static ApiException tripNotFound() {
        return ApiException.notFound("TRIP_NOT_FOUND", "El viaje no existe");
    }

    /** Sin datos del conductor: es para el tópico de la empresa. */
    private static Map<String, Object> raisedPayload(TripAlert alert) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alertId", alert.getId());
        payload.put("itineraryId", alert.getItineraryId());
        payload.put("companyId", alert.getCompanyId());
        payload.put("busId", alert.getBusId());
        payload.put("tipo", alert.getTipo().name());
        payload.put("gravedad", alert.getGravedad().name());
        payload.put("lat", alert.getLat());
        payload.put("lng", alert.getLng());
        payload.put("mensaje", alert.getMensaje());
        payload.put("puedeContinuar", alert.getPuedeContinuar());
        return payload;
    }

    static TripAlertResponse toResponse(TripAlert a) {
        return new TripAlertResponse(a.getId(), a.getItineraryId(), a.getCompanyId(), a.getBusId(), a.getTipo().name(),
                a.getGravedad().name(), a.getLat(), a.getLng(), a.getMensaje(), a.getPuedeContinuar(),
                a.getEstado().name(), a.getCreadoEn(), a.getResueltoEn(), a.getNotaResolucion());
    }
}
