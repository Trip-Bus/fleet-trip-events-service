package com.tripbus.fleet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tripbus.fleet.common.error.ApiException;
import com.tripbus.fleet.domain.FleetItinerary;
import com.tripbus.fleet.repository.FleetItineraryRepository;
import com.tripbus.fleet.web.dto.EtaView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** US-181: lo que las pruebas de integración no alcanzan (Redis caído, choques de códigos, formato). */
class ShareLinkServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-04T12:00:00Z");

    private final FleetItineraryRepository repository = mock(FleetItineraryRepository.class);
    private final EtaStore etaStore = mock(EtaStore.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valores = mock(ValueOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ShareLinkService service = new ShareLinkService(repository, etaStore, redis, objectMapper,
            Clock.fixed(AHORA, ZoneOffset.UTC));
    private final UUID itineraryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valores);
        when(etaStore.find(any())).thenReturn(Optional.empty());
        when(repository.findById(itineraryId)).thenReturn(Optional.of(new FleetItinerary(itineraryId,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Bogotá", "Tunja", AHORA,
                AHORA.plus(Duration.ofHours(3)))));
    }

    @Test
    void losCodigosTienenOchoCaracteresSinAmbiguedad() {
        Set<String> codigos = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String codigo = service.codigo();
            assertThat(codigo).hasSize(ShareLinkService.LARGO).doesNotContainAnyWhitespaces()
                    .matches("[" + ShareLinkService.ALFABETO + "]+")
                    .doesNotContain("0").doesNotContain("O").doesNotContain("1").doesNotContain("I")
                    .doesNotContain("L");
            codigos.add(codigo);
        }
        assertThat(codigos).hasSizeGreaterThan(195);
    }

    @Test
    void siTodosLosCodigosChocanResponde503() {
        when(valores.increment(anyString())).thenReturn(1L);
        when(valores.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> service.crear(itineraryId, UUID.randomUUID()))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "SHARE_UNAVAILABLE");
    }

    @Test
    void siRedisFallaAlCrearOAlLeerResponde503() {
        when(valores.increment(anyString())).thenThrow(new QueryTimeoutException("redis caído"));
        when(valores.get(anyString())).thenThrow(new QueryTimeoutException("redis caído"));

        assertThatThrownBy(() -> service.crear(itineraryId, UUID.randomUUID()))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "SHARE_UNAVAILABLE");
        assertThatThrownBy(() -> service.ver("ABCDEFGH"))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "SHARE_UNAVAILABLE");
    }

    @Test
    void unEnlaceVencidoQueRedisAunGuardaOConViajeBorradoDa404() {
        when(valores.get(ShareLinkService.key("ABCDEFGH")))
                .thenReturn("{\"itineraryId\":\"%s\",\"venceEn\":\"%s\"}".formatted(itineraryId, AHORA));
        UUID borrado = UUID.randomUUID();
        when(valores.get(ShareLinkService.key("HGFEDCBA")))
                .thenReturn("{\"itineraryId\":\"%s\",\"venceEn\":\"%s\"}".formatted(borrado, AHORA.plusSeconds(60)));

        assertThatThrownBy(() -> service.ver("ABCDEFGH"))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "SHARE_LINK_NOT_FOUND");
        assertThatThrownBy(() -> service.ver("HGFEDCBA"))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "SHARE_LINK_NOT_FOUND");
        assertThatThrownBy(() -> service.ver(null))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "SHARE_LINK_NOT_FOUND");
    }

    @Test
    void conEtaYaAlcanzadaUsaLaLlegadaProgramada() {
        FleetItinerary itinerary = repository.findById(itineraryId).orElseThrow();
        when(etaStore.find(itineraryId)).thenReturn(Optional.of(new EtaView(itineraryId, 0,
                AHORA, List.of(new EtaView.Parada(1, "Tunja", AHORA, null, true)))));

        assertThat(service.llegadaEstimada(itinerary)).isEqualTo(itinerary.getFechaLlegada());
    }
}
