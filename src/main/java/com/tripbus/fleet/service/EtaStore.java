package com.tripbus.fleet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.fleet.web.dto.EtaView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** US-180: la última ETA calculada de cada viaje, en Redis (fleet:itinerary:{id}:eta). */
@Component
public class EtaStore {

    private static final Logger log = LoggerFactory.getLogger(EtaStore.class);
    static final Duration TTL = Duration.ofDays(1);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public EtaStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    static String key(UUID itineraryId) {
        return "fleet:itinerary:" + itineraryId + ":eta";
    }

    public void save(EtaView eta) {
        try {
            redis.opsForValue().set(key(eta.itineraryId()), objectMapper.writeValueAsString(eta), TTL);
        } catch (Exception e) {
            log.warn("No se pudo guardar la ETA del viaje {}: {}", eta.itineraryId(), e.getMessage());
        }
    }

    public Optional<EtaView> find(UUID itineraryId) {
        try {
            String json = redis.opsForValue().get(key(itineraryId));
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, EtaView.class));
        } catch (Exception e) {
            log.warn("No se pudo leer la ETA del viaje {}: {}", itineraryId, e.getMessage());
            return Optional.empty();
        }
    }
}
