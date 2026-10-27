package com.tripbus.fleet.service;

import com.tripbus.fleet.common.error.ApiException;
import com.tripbus.fleet.common.geo.Geo;
import com.tripbus.fleet.config.FleetProperties;
import com.tripbus.fleet.domain.FleetItinerary;
import com.tripbus.fleet.domain.TrackingSession;
import com.tripbus.fleet.domain.TrackingStatus;
import com.tripbus.fleet.events.FleetEvents;
import com.tripbus.fleet.repository.FleetItineraryRepository;
import com.tripbus.fleet.repository.TrackingSessionRepository;
import com.tripbus.fleet.repository.TripAssignmentRepository;
import com.tripbus.fleet.web.dto.DriverLocationRequest;
import com.tripbus.fleet.web.dto.LocationResult;
import com.tripbus.fleet.web.dto.PositionRequest;
import com.tripbus.fleet.web.dto.TrackingStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * US-173 a US-175: el bus se rastrea con la ubicación del celular del conductor. El viaje se resuelve
 * con su asignación (US-121) y solo se acepta dentro de la ventana de rastreo y con la sesión
 * encendida. Cada cambio de estado de la sesión publica TrackingStatusChanged por el outbox.
 */
@Service
public class DriverTrackingService {

    private static final Logger log = LoggerFactory.getLogger(DriverTrackingService.class);

    /** Lecturas con timestamp más adelante que esto se descartan (reloj del celular adelantado). */
    static final Duration FUTURO_MAX = Duration.ofSeconds(30);
    /** Velocidad implícita respecto a la última lectura por encima de la cual es un salto imposible. */
    static final double VELOCIDAD_MAX_KMH = 150;
    /** Un viaje asignado a menos de esto de ahora (por su salida) cuenta como "fuera de la ventana". */
    static final Duration VIAJE_CERCANO = Duration.ofHours(24);

    private final TripAssignmentRepository assignmentRepository;
    private final TrackingSessionRepository sessionRepository;
    private final FleetItineraryRepository itineraryRepository;
    private final TrackingWindow trackingWindow;
    private final TelemetryService telemetryService;
    private final Outbox outbox;
    private final FleetProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DriverTrackingService(TripAssignmentRepository assignmentRepository,
                                 TrackingSessionRepository sessionRepository,
                                 FleetItineraryRepository itineraryRepository, TrackingWindow trackingWindow,
                                 TelemetryService telemetryService, Outbox outbox, FleetProperties properties,
                                 TransactionTemplate tx, Clock clock) {
        this.assignmentRepository = assignmentRepository;
        this.sessionRepository = sessionRepository;
        this.itineraryRepository = itineraryRepository;
        this.trackingWindow = trackingWindow;
        this.telemetryService = telemetryService;
        this.outbox = outbox;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    /** POST /driver/tracking/start: enciende el rastreo del viaje activo. */
    @Transactional
    public TrackingStatusResponse start(UUID conductorId) {
        return cambiarPorConductor(conductorId, TrackingStatus.ACTIVO);
    }

    /** POST /driver/tracking/stop: lo pone en pausa. */
    @Transactional
    public TrackingStatusResponse stop(UUID conductorId) {
        return cambiarPorConductor(conductorId, TrackingStatus.EN_PAUSA);
    }

    private TrackingStatusResponse cambiarPorConductor(UUID conductorId, TrackingStatus nuevo) {
        FleetItinerary itinerary = viajeEnVentana(conductorId, clock.instant());
        return toResponse(itinerary, cambiarSesion(itinerary, conductorId, nuevo));
    }

    /** US-171: al iniciar el viaje se enciende el rastreo como con /driver/tracking/start. Requiere transacción. */
    @Transactional(propagation = Propagation.MANDATORY)
    public TrackingSession encender(FleetItinerary itinerary, UUID conductorId) {
        return cambiarSesion(itinerary, conductorId, TrackingStatus.ACTIVO);
    }

    /**
     * US-171: al finalizar el viaje la sesión pasa a FINALIZADO (se crea si nunca se encendió) y
     * desde ahí no se acepta nada más. Requiere transacción.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TrackingSession finalizar(FleetItinerary itinerary) {
        Instant now = clock.instant();
        boolean creada = sessionRepository.crearSimuladaSiNoExiste(itinerary.getItineraryId(), now) == 1;
        TrackingSession session = sessionRepository.findByIdForUpdate(itinerary.getItineraryId()).orElseThrow();
        if (session.cambiar(TrackingStatus.FINALIZADO, now) || creada) {
            publicar(itinerary, session);
        }
        return session;
    }

    private TrackingSession cambiarSesion(FleetItinerary itinerary, UUID conductorId, TrackingStatus nuevo) {
        Instant now = clock.instant();
        boolean creada = sessionRepository.crearSiNoExiste(itinerary.getItineraryId(), conductorId, now) == 1;
        TrackingSession session = sessionRepository.findByIdForUpdate(itinerary.getItineraryId()).orElseThrow();
        if (session.getEstado() == TrackingStatus.FINALIZADO) {
            throw trackingNotActive();
        }
        session.encender(conductorId, now);
        if (session.cambiar(nuevo, now) || creada) {
            publicar(itinerary, session);
        }
        return session;
    }

    /**
     * POST /driver/location y /driver/location/batch: las lecturas se procesan en orden de timestamp.
     * Las que no pasan los filtros se descartan con su motivo sin romper el envío; las aceptadas
     * entran por la ingesta asíncrona de siempre (stream de telemetría).
     */
    @Transactional
    public List<LocationResult> location(UUID conductorId, List<DriverLocationRequest> lecturas) {
        Instant now = clock.instant();
        FleetItinerary itinerary = viajeEnVentana(conductorId, now);
        TrackingSession session = sessionRepository.findByIdForUpdate(itinerary.getItineraryId())
                .filter(s -> s.getEstado().recibePosiciones())
                .orElseThrow(DriverTrackingService::trackingNotActive);

        List<DriverLocationRequest> ordenadas = new ArrayList<>(lecturas);
        ordenadas.sort(Comparator.comparing(l -> timestamp(l, now)));
        List<LocationResult> resultados = new ArrayList<>();
        boolean algunaAceptada = false;
        for (DriverLocationRequest lectura : ordenadas) {
            Instant ts = timestamp(lectura, now);
            String motivo = motivoDescarte(lectura, ts, session, itinerary, now);
            if (motivo != null) {
                resultados.add(new LocationResult(ts, false, motivo, null));
                continue;
            }
            String eventId = telemetryService.enqueue(new PositionRequest(itinerary.getBusId(),
                            itinerary.getItineraryId(), lectura.lat(), lectura.lng(), lectura.velocidad(),
                            lectura.rumbo(), ts), ts, conductorId, lectura.precision());
            session.registrarPosicion(ts, lectura.lat(), lectura.lng());
            resultados.add(new LocationResult(ts, true, null, eventId));
            algunaAceptada = true;
        }
        // Una posición nueva estando en SIN_SENAL lo devuelve a ACTIVO.
        if (algunaAceptada && session.cambiar(TrackingStatus.ACTIVO, now)) {
            publicar(itinerary, session);
        }
        return resultados;
    }

    private String motivoDescarte(DriverLocationRequest lectura, Instant ts, TrackingSession session,
                                  FleetItinerary itinerary, Instant now) {
        if (ts.isAfter(now.plus(FUTURO_MAX))) {
            return "TIMESTAMP_FUTURO";
        }
        if (ts.isBefore(now.minus(properties.maxPositionAge()))) {
            return "POSICION_ANTIGUA";
        }
        if (ts.isBefore(trackingWindow.start(itinerary))) {
            return "FUERA_DE_VENTANA";
        }
        if (lectura.precision() > properties.maxAccuracyM()) {
            return "PRECISION_BAJA";
        }
        if (lectura.lat() == 0.0 && lectura.lng() == 0.0) {
            return "COORDENADAS_INVALIDAS";
        }
        Instant ultima = session.getUltimaPosicionEn();
        if (ultima != null && session.getUltimaLat() != null) {
            if (!ts.isAfter(ultima)) {
                return "POSICION_DESORDENADA";
            }
            double km = Geo.haversineKm(session.getUltimaLat(), session.getUltimaLng(), lectura.lat(), lectura.lng());
            double horas = Duration.between(ultima, ts).toMillis() / 3_600_000.0;
            if (km / horas > VELOCIDAD_MAX_KMH) {
                return "SALTO_IMPOSIBLE";
            }
        }
        return null;
    }

    /**
     * US-175: el viaje asignado, no cancelado, cuya ventana de rastreo contiene este instante.
     * Si tiene un viaje asignado cerca (salida a menos de 24 h) pero fuera de la ventana:
     * 409 OUTSIDE_TRACKING_WINDOW; si no tiene ninguno: 409 NO_ACTIVE_TRIP.
     */
    public FleetItinerary viajeEnVentana(UUID conductorId, Instant now) {
        return assignmentRepository.findDelConductorEnVentana(conductorId, now.plus(trackingWindow.before()),
                        now.minus(trackingWindow.after()))
                .stream().findFirst()
                .map(a -> a.getItinerary())
                .orElseThrow(() -> assignmentRepository.findDelConductorEnVentana(conductorId,
                        now.plus(VIAJE_CERCANO), now.minus(VIAJE_CERCANO)).isEmpty()
                        ? ApiException.conflict("NO_ACTIVE_TRIP", "No tiene ningún viaje asignado en curso")
                        : ApiException.conflict("OUTSIDE_TRACKING_WINDOW",
                        "Su viaje asignado no está dentro de la ventana de rastreo"));
    }

    /**
     * El simulador GPS marca sus viajes como ACTIVO (como haría un conductor real), salvo que ya
     * estén FINALIZADO.
     */
    @Transactional
    public void markSimulated(UUID itineraryId, Instant timestamp, double lat, double lng) {
        Instant now = clock.instant();
        boolean creada = sessionRepository.crearSimuladaSiNoExiste(itineraryId, now) == 1;
        TrackingSession session = sessionRepository.findByIdForUpdate(itineraryId).orElseThrow();
        if (session.getEstado() == TrackingStatus.FINALIZADO) {
            return;
        }
        session.registrarPosicion(timestamp, lat, lng);
        if (session.cambiar(TrackingStatus.ACTIVO, now) || creada) {
            itineraryRepository.findById(itineraryId).ifPresent(it -> publicar(it, session));
        }
    }

    /**
     * Job (cada 15 s, un solo nodo): pasa a FINALIZADO las sesiones cuyo viaje terminó su ventana o
     * se canceló, y a SIN_SENAL las activas sin posiciones por más de tripbus.fleet.signal-timeout.
     * Cada sesión se revisa en su propia transacción. Devuelve cuántas cambiaron.
     */
    public int actualizarEstados() {
        int cambios = 0;
        for (UUID itineraryId : sessionRepository.findAbiertas()) {
            try {
                if (Boolean.TRUE.equals(tx.execute(s -> revisar(itineraryId)))) {
                    cambios++;
                }
            } catch (RuntimeException e) {
                log.warn("No se pudo actualizar el rastreo del viaje {}: {}", itineraryId, e.getMessage());
            }
        }
        return cambios;
    }

    private boolean revisar(UUID itineraryId) {
        TrackingSession session = sessionRepository.findByIdForUpdate(itineraryId).orElseThrow();
        FleetItinerary itinerary = itineraryRepository.findById(itineraryId).orElseThrow();
        Instant now = clock.instant();
        TrackingStatus nuevo = session.getEstado();
        if (itinerary.isCancelado() || now.isAfter(trackingWindow.end(itinerary))) {
            nuevo = TrackingStatus.FINALIZADO;
        } else if (session.getEstado() == TrackingStatus.ACTIVO
                && Duration.between(session.ultimaActividad(), now).compareTo(properties.signalTimeout()) > 0) {
            nuevo = TrackingStatus.SIN_SENAL;
        }
        if (!session.cambiar(nuevo, now)) {
            return false;
        }
        publicar(itinerary, session);
        return true;
    }

    private void publicar(FleetItinerary itinerary, TrackingSession session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("itineraryId", itinerary.getItineraryId());
        payload.put("busId", itinerary.getBusId());
        payload.put("companyId", itinerary.getCompanyId());
        payload.put("estado", session.getEstado().name());
        payload.put("ultimaPosicionEn", session.getUltimaPosicionEn());
        outbox.add(FleetEvents.TRACKING_STATUS_CHANGED, payload);
    }

    private static Instant timestamp(DriverLocationRequest lectura, Instant now) {
        return lectura.timestamp() != null ? lectura.timestamp() : now;
    }

    private static ApiException trackingNotActive() {
        return ApiException.conflict("TRACKING_NOT_ACTIVE", "El rastreo del viaje está en pausa o finalizado");
    }

    private static TrackingStatusResponse toResponse(FleetItinerary itinerary, TrackingSession session) {
        return new TrackingStatusResponse(itinerary.getItineraryId(), itinerary.getBusId(), session.getEstado().name(),
                session.getUltimaPosicionEn());
    }
}
