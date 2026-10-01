package com.tripbus.fleet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.fleet.common.error.ApiException;
import com.tripbus.fleet.config.FleetProperties;
import com.tripbus.fleet.domain.BusPosition;
import com.tripbus.fleet.events.FleetEvents;
import com.tripbus.fleet.repository.BusPositionRepository;
import com.tripbus.fleet.web.dto.PositionRequest;
import com.tripbus.fleet.web.dto.PositionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * US-038. Ingesta asíncrona: la API solo valida y encola la posición en un stream de Redis
 * (respuesta 202 inmediata); un consumidor independiente la persiste, actualiza la
 * "última posición conocida" en Redis y registra PositionUpdated en el outbox.
 */
@Service
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);
    private static final Duration LAST_POSITION_TTL = Duration.ofDays(1);

    private final StringRedisTemplate redis;
    private final BusPositionRepository positionRepository;
    private final Outbox outbox;
    private final ObjectMapper objectMapper;
    private final FleetProperties properties;
    private final Clock clock;
    private final java.util.concurrent.atomic.AtomicLong ingested = new java.util.concurrent.atomic.AtomicLong();

    public TelemetryService(StringRedisTemplate redis, BusPositionRepository positionRepository, Outbox outbox,
                            ObjectMapper objectMapper, FleetProperties properties, Clock clock) {
        this.redis = redis;
        this.positionRepository = positionRepository;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /** Valida y encola. Devuelve el eventId asignado a la posición. */
    public String ingest(PositionRequest request) {
        Instant now = clock.instant();
        Instant timestamp = request.timestamp() != null ? request.timestamp() : now;
        // Validación mínima de GPS (US-040 completa está en el backlog).
        if (timestamp.isBefore(now.minus(properties.maxPositionAge()))) {
            throw ApiException.badRequest("STALE_POSITION", "La posición es demasiado antigua y se descarta");
        }
        if (timestamp.isAfter(now.plus(Duration.ofMinutes(2)))) {
            throw ApiException.badRequest("FUTURE_POSITION", "La posición tiene una fecha futura");
        }
        if (request.lat() == 0.0 && request.lng() == 0.0) {
            throw ApiException.badRequest("INVALID_COORDINATES", "Coordenadas (0,0) inválidas");
        }
        String eventId = UUID.randomUUID().toString();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventId", eventId);
        fields.put("busId", request.busId().toString());
        if (request.itineraryId() != null) {
            fields.put("itineraryId", request.itineraryId().toString());
        }
        fields.put("lat", String.valueOf(request.lat()));
        fields.put("lng", String.valueOf(request.lng()));
        if (request.velocidad() != null) {
            fields.put("velocidad", String.valueOf(request.velocidad()));
        }
        if (request.rumbo() != null) {
            fields.put("rumbo", String.valueOf(request.rumbo()));
        }
        fields.put("timestamp", timestamp.toString());
        try {
            redis.opsForStream().add(StreamRecords.newRecord().in(properties.telemetryStream()).ofMap(fields));
            if (ingested.incrementAndGet() % 1000 == 0) {
                redis.opsForStream().trim(properties.telemetryStream(), 100_000, true);
            }
        } catch (RuntimeException e) {
            throw ApiException.unavailable("TELEMETRY_UNAVAILABLE", "No se pudo encolar la posición");
        }
        return eventId;
    }

    /** Procesa una posición encolada (consumidor). Idempotente por eventId. */
    @Transactional
    public void process(Map<String, String> fields) {
        String eventId = fields.get("eventId");
        if (eventId == null || positionRepository.existsByEventId(eventId)) {
            return;
        }
        UUID busId = UUID.fromString(fields.get("busId"));
        UUID itineraryId = fields.containsKey("itineraryId") ? UUID.fromString(fields.get("itineraryId")) : null;
        BusPosition position = positionRepository.save(new BusPosition(eventId, busId, itineraryId,
                Double.parseDouble(fields.get("lat")), Double.parseDouble(fields.get("lng")),
                parse(fields.get("velocidad")), parse(fields.get("rumbo")), Instant.parse(fields.get("timestamp")),
                clock.instant()));

        PositionResponse view = toView(position);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("busId", busId);
        payload.put("itineraryId", itineraryId);
        payload.put("lat", view.lat());
        payload.put("lng", view.lng());
        payload.put("velocidad", view.velocidad());
        payload.put("rumbo", view.rumbo());
        payload.put("timestamp", view.timestamp());
        outbox.add(FleetEvents.POSITION_UPDATED, payload);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheLastPosition(view);
            }
        });
    }

    public Optional<PositionResponse> lastByBus(UUID busId) {
        return fromCache("fleet:bus:" + busId + ":last")
                .or(() -> positionRepository.findFirstByBusIdOrderByRegistradoEnDesc(busId).map(TelemetryService::toView));
    }

    public Optional<PositionResponse> lastByItinerary(UUID itineraryId) {
        return fromCache("fleet:itinerary:" + itineraryId + ":last")
                .or(() -> positionRepository.findFirstByItineraryIdOrderByRegistradoEnDesc(itineraryId)
                        .map(TelemetryService::toView));
    }

    private void cacheLastPosition(PositionResponse view) {
        try {
            String json = objectMapper.writeValueAsString(view);
            redis.opsForValue().set("fleet:bus:" + view.busId() + ":last", json, LAST_POSITION_TTL);
            if (view.itineraryId() != null) {
                redis.opsForValue().set("fleet:itinerary:" + view.itineraryId() + ":last", json, LAST_POSITION_TTL);
            }
        } catch (Exception e) {
            log.debug("No se pudo cachear la última posición: {}", e.getMessage());
        }
    }

    private Optional<PositionResponse> fromCache(String key) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, PositionResponse.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Double parse(String value) {
        return value == null ? null : Double.valueOf(value);
    }

    static PositionResponse toView(BusPosition p) {
        return new PositionResponse(p.getBusId(), p.getItineraryId(), p.getLat(), p.getLng(), p.getVelocidad(),
                p.getRumbo(), p.getRegistradoEn());
    }

    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 4 * * *", zone = "America/Bogota")
    @Transactional
    public void purgeOldPositions() {
        int deleted = positionRepository.deleteOlderThan(clock.instant().minus(Duration.ofDays(7)));
        log.info("Posiciones antiguas eliminadas: {}", deleted);
    }
}
