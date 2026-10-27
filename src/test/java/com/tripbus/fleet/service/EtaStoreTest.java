package com.tripbus.fleet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tripbus.fleet.web.dto.EtaView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EtaStoreTest {

    private final UUID viaje = UUID.randomUUID();
    private ValueOperations<String, String> valores;
    private EtaStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        valores = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valores);
        store = new EtaStore(redis, new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    private EtaView eta() {
        return new EtaView(viaje, 7, Instant.parse("2026-10-26T12:00:00Z"),
                List.of(new EtaView.Parada(1, "Bogotá", Instant.parse("2026-10-26T11:00:00Z"), null, true)));
    }

    @Test
    void guardaYLeeLaEtaDelViaje() throws Exception {
        store.save(eta());
        verify(valores).set(eq("fleet:itinerary:" + viaje + ":eta"), anyString(), eq(EtaStore.TTL));

        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(eta());
        when(valores.get("fleet:itinerary:" + viaje + ":eta")).thenReturn(json);
        assertThat(store.find(viaje)).contains(eta());
        assertThat(store.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void sinRedisNoFalla() {
        doThrow(new RedisConnectionFailureException("caído")).when(valores).set(anyString(), anyString(), any());
        when(valores.get(anyString())).thenThrow(new RedisConnectionFailureException("caído"));

        assertThatCode(() -> store.save(eta())).doesNotThrowAnyException();
        assertThat(store.find(viaje)).isEmpty();
    }

    @Test
    void unaEtaIlegibleSeIgnora() {
        when(valores.get(anyString())).thenReturn("{no es json");

        assertThat(store.find(viaje)).isEmpty();
    }
}
