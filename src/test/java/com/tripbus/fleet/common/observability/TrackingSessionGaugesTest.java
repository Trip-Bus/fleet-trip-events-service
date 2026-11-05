package com.tripbus.fleet.common.observability;

import com.tripbus.fleet.domain.TrackingStatus;
import com.tripbus.fleet.repository.TrackingSessionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrackingSessionGaugesTest {

    /** Reloj que avanza a mano. */
    private static final class Reloj extends Clock {
        private Instant ahora = Instant.parse("2026-11-04T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }

    private final TrackingSessionRepository repository = mock(TrackingSessionRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Reloj reloj = new Reloj();
    private final TrackingSessionGauges gauges = new TrackingSessionGauges(repository, registry, reloj);

    private double activas() {
        return registry.get(TrackingSessionGauges.NOMBRE).tag("estado", "ACTIVO").gauge().value();
    }

    @Test
    void reutilizaElConteoMientrasEstaVigenteYLuegoLoRefresca() {
        when(repository.contarPorEstado()).thenReturn(List.<Object[]>of(new Object[]{TrackingStatus.ACTIVO, 2L}),
                List.<Object[]>of(new Object[]{TrackingStatus.ACTIVO, 5L}));

        assertThat(activas()).isEqualTo(2.0);
        assertThat(gauges.valor(TrackingStatus.EN_PAUSA)).isZero();
        reloj.ahora = reloj.ahora.plusSeconds(5);
        assertThat(activas()).isEqualTo(2.0);
        verify(repository, times(1)).contarPorEstado();

        reloj.ahora = reloj.ahora.plus(TrackingSessionGauges.VIGENCIA);
        assertThat(activas()).isEqualTo(5.0);
        verify(repository, times(2)).contarPorEstado();
    }

    @Test
    void siLaBaseFallaConservaLosUltimosValores() {
        when(repository.contarPorEstado()).thenReturn(List.<Object[]>of(new Object[]{TrackingStatus.ACTIVO, 4L}))
                .thenThrow(new IllegalStateException("base caída"));

        assertThat(activas()).isEqualTo(4.0);
        reloj.ahora = reloj.ahora.plus(TrackingSessionGauges.VIGENCIA);
        assertThat(activas()).isEqualTo(4.0);
    }
}
