package com.tripbus.fleet.service;

import com.tripbus.fleet.common.redis.LeaderLock;
import com.tripbus.fleet.config.FleetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consumidor independiente de la ingesta GPS (stream tripbus:telemetry, consumer group propio).
 * US-038: solo hace ACK de lo que se procesó bien. Lo que falla queda pendiente en el grupo y un
 * job (en un solo nodo, {@link LeaderLock}) lo reclama y lo reintenta; tras el máximo de entregas
 * lo mueve a la DLQ. Reintentar es seguro: {@link TelemetryService#process} es idempotente por eventId.
 */
@Component
public class TelemetryConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelemetryConsumer.class);
    private static final int RETRY_BATCH = 100;

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redis;
    private final TelemetryService telemetryService;
    private final FleetProperties properties;
    private final LeaderLock leaderLock;
    private final boolean enabled;
    private final String consumerName = "t-" + UUID.randomUUID().toString().substring(0, 8);
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private volatile boolean running;

    public TelemetryConsumer(RedisConnectionFactory connectionFactory, StringRedisTemplate redis,
                             TelemetryService telemetryService, FleetProperties properties, LeaderLock leaderLock,
                             @org.springframework.beans.factory.annotation.Value("${tripbus.events.consumer-enabled:true}")
                             boolean enabled) {
        this.connectionFactory = connectionFactory;
        this.redis = redis;
        this.telemetryService = telemetryService;
        this.properties = properties;
        this.leaderLock = leaderLock;
        this.enabled = enabled;
    }

    @Override
    public void start() {
        if (!enabled) {
            return;
        }
        ensureGroup();
        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                .pollTimeout(Duration.ofSeconds(1))
                .batchSize(100)
                .build();
        container = StreamMessageListenerContainer.create(connectionFactory, options);
        var request = StreamMessageListenerContainer.StreamReadRequest
                .builder(StreamOffset.create(properties.telemetryStream(), ReadOffset.lastConsumed()))
                .consumer(Consumer.from(properties.telemetryGroup(), consumerName))
                .autoAcknowledge(false)
                .cancelOnError(t -> false)
                .errorHandler(t -> {
                    if (String.valueOf(t.getMessage()).contains("NOGROUP")) {
                        ensureGroup();
                    } else {
                        log.warn("Error leyendo telemetría: {}", t.getMessage());
                    }
                })
                .build();
        container.register(request, record -> {
            if (tryProcess(record.getId(), record.getValue())) {
                acknowledge(record.getId());
            }
        });
        container.start();
        running = true;
    }

    /** Procesa la posición; si falla no hay ACK y el mensaje queda pendiente para el reintento. */
    private boolean tryProcess(RecordId id, Map<String, String> fields) {
        try {
            telemetryService.process(fields);
            return true;
        } catch (Exception e) {
            log.warn("Posición {} (eventId {}) no procesada, se reintentará: {}", id, fields.get("eventId"),
                    e.getMessage());
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${tripbus.jobs.telemetry-retry-ms:30000}", initialDelay = 30000)
    public void retryTick() {
        if (enabled) {
            leaderLock.runIfLeader("fleet-telemetry-retry", Duration.ofSeconds(25), this::retryPending);
        }
    }

    /**
     * Reclama (XCLAIM) los pendientes del grupo que llevan más de telemetry-retry-idle sin ACK y los
     * reintenta. Los que ya agotaron sus entregas van a la DLQ con ACK, para no reintentarlos más.
     */
    public void retryPending() {
        String stream = properties.telemetryStream();
        Duration minIdle = properties.telemetryRetryIdle();
        for (PendingMessage pending : redis.opsForStream()
                .pending(stream, properties.telemetryGroup(), Range.unbounded(), RETRY_BATCH)) {
            if (pending.getElapsedTimeSinceLastDelivery().compareTo(minIdle) < 0) {
                continue;
            }
            List<MapRecord<String, Object, Object>> claimed = redis.opsForStream()
                    .claim(stream, properties.telemetryGroup(), consumerName, minIdle, pending.getId());
            if (claimed.isEmpty()) {
                // Otro consumidor lo reclamó antes, o el mensaje ya no está en el stream (XTRIM).
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            claimed.get(0).getValue().forEach((k, v) -> fields.put(k.toString(), v.toString()));
            if (pending.getTotalDeliveryCount() >= properties.telemetryMaxDeliveries()) {
                deadLetter(pending, fields);
            } else if (tryProcess(pending.getId(), fields)) {
                acknowledge(pending.getId());
            }
        }
    }

    private void deadLetter(PendingMessage pending, Map<String, String> fields) {
        Map<String, String> dlq = new LinkedHashMap<>(fields);
        dlq.put("originalId", pending.getIdAsString());
        dlq.put("deliveries", String.valueOf(pending.getTotalDeliveryCount()));
        redis.opsForStream().add(StreamRecords.newRecord().in(properties.telemetryDlqStream()).ofMap(dlq));
        acknowledge(pending.getId());
        log.error("Posición enviada a la DLQ {} tras {} entregas fallidas: eventId {}",
                properties.telemetryDlqStream(), pending.getTotalDeliveryCount(), fields.get("eventId"));
    }

    private void acknowledge(RecordId id) {
        redis.opsForStream().acknowledge(properties.telemetryStream(), properties.telemetryGroup(), id);
    }

    private void ensureGroup() {
        try {
            byte[] key = properties.telemetryStream().getBytes(StandardCharsets.UTF_8);
            redis.execute((RedisCallback<String>) c ->
                    c.streamCommands().xGroupCreate(key, properties.telemetryGroup(), ReadOffset.from("0-0"), true));
        } catch (Exception e) {
            // BUSYGROUP: ya existe
        }
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
