package com.tripbus.fleet.service;

import com.tripbus.fleet.config.FleetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/** Consumidor independiente de la ingesta GPS (stream tripbus:telemetry, consumer group propio). */
@Component
public class TelemetryConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelemetryConsumer.class);

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redis;
    private final TelemetryService telemetryService;
    private final FleetProperties properties;
    private final boolean enabled;
    private final String consumerName = "t-" + UUID.randomUUID().toString().substring(0, 8);
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private volatile boolean running;

    public TelemetryConsumer(RedisConnectionFactory connectionFactory, StringRedisTemplate redis,
                             TelemetryService telemetryService, FleetProperties properties,
                             @org.springframework.beans.factory.annotation.Value("${tripbus.events.consumer-enabled:true}")
                             boolean enabled) {
        this.connectionFactory = connectionFactory;
        this.redis = redis;
        this.telemetryService = telemetryService;
        this.properties = properties;
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
            try {
                telemetryService.process(record.getValue());
            } catch (Exception e) {
                log.error("Posición descartada {}: {}", record.getId(), e.getMessage());
            }
            redis.opsForStream().acknowledge(properties.telemetryStream(), properties.telemetryGroup(), record.getId());
        });
        container.start();
        running = true;
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
