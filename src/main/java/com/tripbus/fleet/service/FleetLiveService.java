package com.tripbus.fleet.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.fleet.config.DetectionProperties;
import com.tripbus.fleet.domain.DriverRole;
import com.tripbus.fleet.domain.FleetItinerary;
import com.tripbus.fleet.domain.TrackingSession;
import com.tripbus.fleet.domain.TrackingStatus;
import com.tripbus.fleet.domain.TripAlert;
import com.tripbus.fleet.domain.TripAssignment;
import com.tripbus.fleet.domain.TripEventType;
import com.tripbus.fleet.repository.FleetItineraryRepository;
import com.tripbus.fleet.repository.TrackingSessionRepository;
import com.tripbus.fleet.repository.TripAlertRepository;
import com.tripbus.fleet.repository.TripAssignmentRepository;
import com.tripbus.fleet.web.dto.EtaView;
import com.tripbus.fleet.web.dto.FleetLiveResponse;
import com.tripbus.fleet.web.dto.PositionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * US-178: el mapa de la flota del operador. Por respuesta se hacen pocas consultas sin importar
 * cuántos viajes haya: una de viajes (máximo {@link #MAX_VIAJES}), una de sesiones de rastreo, una de
 * alertas abiertas, una de conductores principales y un solo MGET en Redis con la última posición y
 * la ETA de todos los viajes. Los nombres de los conductores salen de {@link DriverNameCache}.
 */
@Service
public class FleetLiveService {

    static final int MAX_VIAJES = 300;

    private static final Logger log = LoggerFactory.getLogger(FleetLiveService.class);

    private final FleetItineraryRepository itineraryRepository;
    private final TrackingSessionRepository sessionRepository;
    private final TripAlertRepository alertRepository;
    private final TripAssignmentRepository assignmentRepository;
    private final DriverNameCache driverNames;
    private final TrackingWindow trackingWindow;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final DetectionProperties detection;
    private final Clock clock;

    public FleetLiveService(FleetItineraryRepository itineraryRepository, TrackingSessionRepository sessionRepository,
                            TripAlertRepository alertRepository, TripAssignmentRepository assignmentRepository,
                            DriverNameCache driverNames, TrackingWindow trackingWindow, StringRedisTemplate redis,
                            ObjectMapper objectMapper, DetectionProperties detection, Clock clock) {
        this.itineraryRepository = itineraryRepository;
        this.sessionRepository = sessionRepository;
        this.alertRepository = alertRepository;
        this.assignmentRepository = assignmentRepository;
        this.driverNames = driverNames;
        this.trackingWindow = trackingWindow;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.detection = detection;
        this.clock = clock;
    }

    /**
     * GET /fleet/live. El OPERADOR ve solo su empresa (sin empresa en el token, nada); el ADMIN la
     * empresa que pida o, sin companyId, todas.
     */
    @Transactional(readOnly = true)
    public FleetLiveResponse live(boolean isAdmin, UUID companyDelUsuario, UUID companyPedida) {
        Instant now = clock.instant();
        UUID company = isAdmin ? companyPedida : companyDelUsuario;
        List<FleetItinerary> viajes = isAdmin || company != null ? enVentana(company, now) : List.of();
        if (viajes.isEmpty()) {
            return new FleetLiveResponse(now, resumen(List.of()), List.of());
        }

        List<UUID> ids = viajes.stream().map(FleetItinerary::getItineraryId).toList();
        Map<UUID, TrackingSession> sesiones = sessionRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(TrackingSession::getItineraryId, Function.identity()));
        Map<UUID, List<TripAlert>> alertas = alertRepository.findByItineraryIdInAndEstado(ids, TripAlert.Estado.ABIERTA)
                .stream().collect(Collectors.groupingBy(TripAlert::getItineraryId));
        Map<UUID, UUID> conductores = assignmentRepository.findByViajesYRol(ids, DriverRole.PRINCIPAL).stream()
                .collect(Collectors.toMap(a -> a.getItinerary().getItineraryId(), TripAssignment::getConductorId));
        Map<UUID, String> nombres = driverNames.nombres(new HashSet<>(conductores.values()));
        Map<String, String> redisValues = leerRedis(ids);

        List<FleetLiveResponse.Viaje> items = new ArrayList<>(viajes.size());
        for (FleetItinerary it : viajes) {
            UUID id = it.getItineraryId();
            PositionResponse posicion = parse(redisValues.get(lastKey(id)), PositionResponse.class);
            EtaView eta = parse(redisValues.get(EtaStore.key(id)), EtaView.class);
            TrackingSession sesion = sesiones.get(id);
            UUID conductorId = conductores.get(id);
            items.add(new FleetLiveResponse.Viaje(id, it.getBusId(), it.getOrigen(), it.getDestino(),
                    it.getFechaSalida(), it.getFechaLlegada(), it.isIniciado(), it.isFinalizado(),
                    sesion != null ? sesion.getEstado().name() : null,
                    posicion != null ? new FleetLiveResponse.Posicion(posicion.lat(), posicion.lng(),
                            posicion.velocidad(), posicion.rumbo(), posicion.timestamp()) : null,
                    eta != null ? eta.retrasoMinutos() : null,
                    siguienteParada(eta), ultimaParadaAlcanzada(eta),
                    alertas(alertas.getOrDefault(id, List.of())),
                    conductorId != null ? new FleetLiveResponse.Conductor(conductorId, nombres.get(conductorId)) : null));
        }
        return new FleetLiveResponse(now, resumen(items), items);
    }

    /** La ventana [fechaSalida - before, fechaLlegada + after] contiene now (misma regla que TrackingWindow). */
    private List<FleetItinerary> enVentana(UUID company, Instant now) {
        Instant salidaHasta = now.plus(trackingWindow.before());
        Instant llegadaDesde = now.minus(trackingWindow.after());
        Pageable max = PageRequest.of(0, MAX_VIAJES);
        return company != null
                ? itineraryRepository.findEnVentanaDeEmpresa(company, salidaHasta, llegadaDesde, max)
                : itineraryRepository.findEnVentanaTodas(salidaHasta, llegadaDesde, max);
    }

    /** Un solo MGET con la última posición y la ETA de todos los viajes. Si Redis falla, el mapa carga sin ellas. */
    private Map<String, String> leerRedis(List<UUID> ids) {
        List<String> keys = new ArrayList<>(ids.size() * 2);
        for (UUID id : ids) {
            keys.add(lastKey(id));
            keys.add(EtaStore.key(id));
        }
        List<String> values;
        try {
            values = redis.opsForValue().multiGet(keys);
        } catch (RuntimeException e) {
            log.warn("No se pudieron leer posiciones y ETA del mapa de la flota: {}", e.getMessage());
            return Collections.emptyMap();
        }
        Map<String, String> porKey = new HashMap<>();
        if (values != null) {
            for (int i = 0; i < keys.size() && i < values.size(); i++) {
                porKey.put(keys.get(i), values.get(i));
            }
        }
        return porKey;
    }

    private <T> T parse(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.debug("Valor de Redis ilegible para el mapa de la flota: {}", e.getMessage());
            return null;
        }
    }

    static String lastKey(UUID itineraryId) {
        return "fleet:itinerary:" + itineraryId + ":last";
    }

    private static FleetLiveResponse.Parada siguienteParada(EtaView eta) {
        if (eta == null) {
            return null;
        }
        return eta.paradas().stream().filter(p -> !p.alcanzada()).findFirst()
                .map(p -> new FleetLiveResponse.Parada(p.orden(), p.nombre(), p.programada(), p.estimada()))
                .orElse(null);
    }

    private static FleetLiveResponse.Parada ultimaParadaAlcanzada(EtaView eta) {
        if (eta == null) {
            return null;
        }
        return eta.paradas().stream().filter(EtaView.Parada::alcanzada).reduce((a, b) -> b)
                .map(p -> new FleetLiveResponse.Parada(p.orden(), p.nombre(), p.programada(), null))
                .orElse(null);
    }

    private static FleetLiveResponse.Alertas alertas(List<TripAlert> abiertas) {
        String mayor = abiertas.stream().map(TripAlert::getGravedad).max(Comparator.naturalOrder())
                .map(TripEventType.Gravedad::name).orElse(null);
        return new FleetLiveResponse.Alertas(abiertas.size(), mayor);
    }

    private FleetLiveResponse.Resumen resumen(List<FleetLiveResponse.Viaje> viajes) {
        int umbral = detection.delayThresholdMin();
        return new FleetLiveResponse.Resumen(viajes.size(),
                contar(viajes, v -> v.iniciado() && !v.finalizado()),
                contar(viajes, v -> TrackingStatus.ACTIVO.name().equals(v.estadoRastreo())),
                contar(viajes, v -> TrackingStatus.EN_PAUSA.name().equals(v.estadoRastreo())),
                contar(viajes, v -> TrackingStatus.SIN_SENAL.name().equals(v.estadoRastreo())),
                contar(viajes, v -> v.retrasoMinutos() != null && v.retrasoMinutos() > umbral),
                contar(viajes, v -> v.alertas().abiertas() > 0));
    }

    private static int contar(List<FleetLiveResponse.Viaje> viajes,
                              Predicate<FleetLiveResponse.Viaje> condicion) {
        return (int) viajes.stream().filter(condicion).count();
    }
}
