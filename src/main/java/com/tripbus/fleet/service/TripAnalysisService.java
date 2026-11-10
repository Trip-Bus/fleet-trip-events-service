package com.tripbus.fleet.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.fleet.common.geo.Geo;
import com.tripbus.fleet.config.DetectionProperties;
import com.tripbus.fleet.domain.FleetItinerary;
import com.tripbus.fleet.domain.RouteStop;
import com.tripbus.fleet.domain.TripEventType;
import com.tripbus.fleet.events.FleetEvents;
import com.tripbus.fleet.repository.FleetItineraryRepository;
import com.tripbus.fleet.repository.TripEventRepository;
import com.tripbus.fleet.web.dto.EtaView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * US-177 (detección automática de llegada a parada, retraso y desvío) y US-180 (hora estimada por
 * parada), al procesar cada posición aceptada de un viaje en ventana, del conductor o del simulador.
 *
 * <p>Corre después del commit de la posición y en su propia transacción: si algo falla aquí, la
 * posición ya quedó guardada. Por posición se consulta la base una vez (el viaje, bloqueado para que
 * dos posiciones del mismo viaje no se pisen) y, solo al llegar a una parada, una más para no
 * duplicar su LLEGADA_PARADA. Las paradas salen de RouteStopsCache (Redis) y el estado entre
 * lecturas (últimas posiciones, desvío, último retraso avisado, ETA publicada) vive en Redis.
 */
@Service
public class TripAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(TripAnalysisService.class);

    /** Las novedades automáticas llevan este origen (en la columna y en el detalle). */
    static final String ORIGEN = "AUTOMATICO";
    static final int LECTURAS_DESVIO = 3;
    static final long RETRASO_REPETIR_MIN = 15;
    static final long CAMBIO_ETA_SEGUNDOS = 60;
    static final Duration TTL = Duration.ofHours(12);
    private static final String DETALLE_ORIGEN = "origen";

    private final FleetItineraryRepository itineraryRepository;
    private final RouteStopsCache stopsCache;
    private final TripEventRepository eventRepository;
    private final TripService tripService;
    private final TripAlertService alertService;
    private final EtaStore etaStore;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Outbox outbox;
    private final DetectionProperties properties;
    private final TransactionTemplate nuevaTransaccion;
    private final Clock clock;

    public TripAnalysisService(FleetItineraryRepository itineraryRepository, RouteStopsCache stopsCache,
                               TripEventRepository eventRepository, TripService tripService,
                               TripAlertService alertService, EtaStore etaStore, StringRedisTemplate redis,
                               ObjectMapper objectMapper, Outbox outbox, DetectionProperties properties,
                               PlatformTransactionManager transactionManager, Clock clock) {
        this.itineraryRepository = itineraryRepository;
        this.stopsCache = stopsCache;
        this.eventRepository = eventRepository;
        this.tripService = tripService;
        this.alertService = alertService;
        this.etaStore = etaStore;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.outbox = outbox;
        this.properties = properties;
        this.nuevaTransaccion = new TransactionTemplate(transactionManager);
        this.nuevaTransaccion.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPositionAccepted(PositionAccepted position) {
        try {
            nuevaTransaccion.executeWithoutResult(status -> analizar(position));
        } catch (RuntimeException e) {
            log.warn("No se pudo analizar la posición del viaje {}: {}", position.itineraryId(), e.getMessage());
        }
    }

    void analizar(PositionAccepted position) {
        FleetItinerary itinerary = itineraryRepository.findByIdForUpdate(position.itineraryId()).orElse(null);
        if (itinerary == null || itinerary.isCancelado() || itinerary.isFinalizado()) {
            return;
        }
        List<RouteStop> stops = stopsCache.stops(itinerary.getRouteId());
        if (stops.size() < 2) {
            return;
        }
        Geo.Punto punto = new Geo.Punto(position.lat(), position.lng());
        detectarLlegada(itinerary, stops, punto);

        Double velocidad = velocidadReciente(itinerary.getItineraryId(), punto, position.timestamp());
        EtaCalculator.Resultado eta = EtaCalculator.calcular(stops, itinerary.getFechaSalida(),
                itinerary.getUltimaParadaReal(), punto, position.timestamp(), velocidad);
        EtaView view = new EtaView(itinerary.getItineraryId(), eta.retrasoMinutos(), position.timestamp(),
                eta.paradas());
        etaStore.save(view);
        publicarSiCambio(view, itinerary.getCompanyId());

        if (!eta.todasAlcanzadas()) {
            detectarRetraso(itinerary, eta.retrasoMinutos());
        }
        detectarDesvio(itinerary, stops, punto);
    }

    /**
     * Llegada a parada: la primera parada pendiente (en orden) a menos del radio. Una sola vez por
     * parada (ultima_parada_real) y sin duplicar la LLEGADA_PARADA si ya existe (p. ej. del simulador).
     */
    private void detectarLlegada(FleetItinerary itinerary, List<RouteStop> stops, Geo.Punto punto) {
        for (int i = 0; i < stops.size(); i++) {
            RouteStop stop = stops.get(i);
            boolean extremo = i == 0 || i == stops.size() - 1;
            double radio = extremo ? properties.stopRadiusEndsM() : properties.stopRadiusM();
            if (stop.getOrden() > itinerary.getUltimaParadaReal()
                    && Geo.distanciaM(punto, EtaCalculator.punto(stop)) <= radio) {
                itinerary.alcanzarParada(stop.getOrden());
                if (!eventRepository.existsLlegada(itinerary.getItineraryId(), stop.getOrden())) {
                    Map<String, Object> detalle = new LinkedHashMap<>();
                    detalle.put("parada", stop.getNombre());
                    detalle.put("orden", stop.getOrden());
                    detalle.put(DETALLE_ORIGEN, ORIGEN);
                    tripService.record(itinerary, TripEventType.LLEGADA_PARADA, "Llegada a " + stop.getNombre(),
                            detalle, null, ORIGEN);
                }
                return;
            }
        }
    }

    /** RETRASO desde el umbral, y de nuevo solo si creció 15 minutos más desde el último aviso. */
    private void detectarRetraso(FleetItinerary itinerary, long minutos) {
        if (minutos < properties.delayThresholdMin()) {
            return;
        }
        String key = key(itinerary.getItineraryId(), "retraso-avisado");
        String avisado = redis.opsForValue().get(key);
        if (avisado != null && minutos < Long.parseLong(avisado) + RETRASO_REPETIR_MIN) {
            return;
        }
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("minutos", minutos);
        detalle.put(DETALLE_ORIGEN, ORIGEN);
        tripService.record(itinerary, TripEventType.RETRASO, "Retraso de " + minutos + " min", detalle, null, ORIGEN);
        redis.opsForValue().set(key, String.valueOf(minutos), TTL);
    }

    /** DESVIO tras 3 lecturas seguidas lejos de la ruta; una sola alerta de desvío abierta por viaje. */
    private void detectarDesvio(FleetItinerary itinerary, List<RouteStop> stops, Geo.Punto punto) {
        String key = key(itinerary.getItineraryId(), "desvio");
        double distancia = Geo.distanciaAPolilineaM(punto, stops.stream().map(EtaCalculator::punto).toList());
        if (distancia <= properties.deviationM()) {
            redis.delete(key);
            return;
        }
        Long seguidas = redis.opsForValue().increment(key);
        redis.expire(key, TTL);
        long metros = Math.round(distancia);
        if (Objects.equals(seguidas, (long) LECTURAS_DESVIO)
                && alertService.raiseDesvio(itinerary, punto.lat(), punto.lng(),
                "El bus está a " + metros + " m de la ruta")) {
            Map<String, Object> detalle = new LinkedHashMap<>();
            detalle.put("distanciaM", metros);
            detalle.put(DETALLE_ORIGEN, ORIGEN);
            tripService.record(itinerary, TripEventType.DESVIO, "Desvío de " + metros + " m de la ruta", detalle,
                    null, ORIGEN);
        }
    }

    /** Velocidad promedio (km/h) de las últimas 3 lecturas del viaje, o null si no hay suficientes. */
    private Double velocidadReciente(UUID itineraryId, Geo.Punto punto, Instant timestamp) {
        String key = key(itineraryId, "recientes");
        redis.opsForList().leftPush(key, punto.lat() + "," + punto.lng() + "," + timestamp.toEpochMilli());
        redis.opsForList().trim(key, 0, LECTURAS_DESVIO - 1L);
        redis.expire(key, TTL);
        List<String> recientes = redis.opsForList().range(key, 0, LECTURAS_DESVIO - 1L);
        if (recientes == null || recientes.size() < 2) {
            return null;
        }
        List<String[]> lecturas = new ArrayList<>(recientes.stream().map(r -> r.split(",")).toList());
        lecturas.sort(Comparator.comparingLong(r -> Long.parseLong(r[2])));
        double km = 0;
        for (int i = 1; i < lecturas.size(); i++) {
            km += Geo.distanciaM(lectura(lecturas.get(i - 1)), lectura(lecturas.get(i))) / 1000;
        }
        long ms = Long.parseLong(lecturas.get(lecturas.size() - 1)[2]) - Long.parseLong(lecturas.get(0)[2]);
        return ms <= 0 ? null : km / (ms / 3_600_000.0);
    }

    /**
     * EtaUpdated solo si alguna ETA cambió 1 minuto o más (o una parada pasó a alcanzada) respecto a la
     * última publicada, y como máximo una vez por tripbus.fleet.eta-publish-interval por viaje.
     */
    private void publicarSiCambio(EtaView view, UUID companyId) {
        String keyEstimadas = key(view.itineraryId(), "eta-publicada");
        String keyEn = key(view.itineraryId(), "eta-publicada-en");
        Map<String, Long> actuales = estimadas(view);
        if (!cambio(redis.opsForValue().get(keyEstimadas), actuales)) {
            return;
        }
        Instant now = clock.instant();
        String publicadaEn = redis.opsForValue().get(keyEn);
        if (publicadaEn != null && Duration.between(Instant.ofEpochMilli(Long.parseLong(publicadaEn)), now)
                .compareTo(properties.etaPublishInterval()) < 0) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("itineraryId", view.itineraryId());
        // US-178: para el mapa de la flota del operador.
        payload.put("companyId", companyId);
        payload.put("retrasoMinutos", view.retrasoMinutos());
        payload.put("paradas", view.paradas());
        outbox.add(FleetEvents.ETA_UPDATED, payload);
        try {
            redis.opsForValue().set(keyEstimadas, objectMapper.writeValueAsString(actuales), TTL);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        redis.opsForValue().set(keyEn, String.valueOf(now.toEpochMilli()), TTL);
    }

    /** orden → ETA en segundos epoch (−1 si ya se alcanzó). */
    private static Map<String, Long> estimadas(EtaView view) {
        Map<String, Long> estimadas = new LinkedHashMap<>();
        for (EtaView.Parada p : view.paradas()) {
            estimadas.put(String.valueOf(p.orden()), p.estimada() == null ? -1L : p.estimada().getEpochSecond());
        }
        return estimadas;
    }

    private boolean cambio(String anteriorJson, Map<String, Long> actuales) {
        if (anteriorJson == null) {
            return true;
        }
        Map<String, Long> anteriores;
        try {
            anteriores = objectMapper.readValue(anteriorJson, new TypeReference<Map<String, Long>>() {
            });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return true;
        }
        if (!anteriores.keySet().equals(actuales.keySet())) {
            return true;
        }
        for (Map.Entry<String, Long> actual : actuales.entrySet()) {
            long antes = anteriores.get(actual.getKey());
            long ahora = actual.getValue();
            if ((antes < 0) != (ahora < 0) || Math.abs(ahora - antes) >= CAMBIO_ETA_SEGUNDOS) {
                return true;
            }
        }
        return false;
    }

    private static Geo.Punto lectura(String[] r) {
        return new Geo.Punto(Double.parseDouble(r[0]), Double.parseDouble(r[1]));
    }

    static String key(UUID itineraryId, String sufijo) {
        return "fleet:itinerary:" + itineraryId + ":" + sufijo;
    }
}
