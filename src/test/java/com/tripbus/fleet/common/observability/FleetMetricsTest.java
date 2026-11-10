package com.tripbus.fleet.common.observability;

import com.tripbus.fleet.domain.TrackingStatus;
import com.tripbus.fleet.domain.TripEventType;
import com.tripbus.fleet.repository.TrackingSessionRepository;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** HT-10: nombres y etiquetas de las métricas propias tal como salen en Prometheus. */
class FleetMetricsTest {

    private PrometheusMeterRegistry registry;
    private FleetMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new MetricsConfig().nombresEnEspanol().customize(registry);
        metrics = new FleetMetrics(registry);
    }

    @Test
    void posicionesAceptadasYDescartadasConSuMotivo() {
        metrics.posicionAceptada();
        metrics.posicionAceptada();
        metrics.posicionDescartada("PRECISION_BAJA");

        String scrape = registry.scrape();
        assertThat(scrape)
                .contains("tripbus_posiciones_total{motivo=\"ninguno\",resultado=\"aceptada\"} 2.0")
                .contains("tripbus_posiciones_total{motivo=\"PRECISION_BAJA\",resultado=\"descartada\"} 1.0");
    }

    @Test
    void alertasPorTipoYGravedad() {
        metrics.alerta(TripEventType.SOS, TripEventType.Gravedad.CRITICA);

        assertThat(registry.scrape()).contains("tripbus_alertas_total{gravedad=\"CRITICA\",tipo=\"SOS\"} 1.0");
    }

    @Test
    void elTimerDeProcesarPosicionSaleEnSegundos() {
        metrics.procesarPosicion(() -> {
        });

        String scrape = registry.scrape();
        assertThat(scrape).contains("tripbus_procesar_posicion_segundos_count 1")
                .contains("tripbus_procesar_posicion_segundos_sum")
                .doesNotContain("tripbus_procesar_posicion_seconds");
    }

    @Test
    void losDemasTimerSiguenConLaConvencionDeSiempre() {
        registry.timer("http.server.requests").record(() -> {
        });

        assertThat(registry.scrape()).contains("http_server_requests_seconds_count 1");
    }

    @Test
    void gaugeDeSesionesPorEstado() {
        TrackingSessionRepository repository = mock(TrackingSessionRepository.class);
        when(repository.contarPorEstado()).thenReturn(List.of(
                new Object[]{TrackingStatus.ACTIVO, 3L}, new Object[]{TrackingStatus.SIN_SENAL, 1L}));
        new TrackingSessionGauges(repository, registry, Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"),
                ZoneOffset.UTC));

        String scrape = registry.scrape();
        assertThat(scrape).contains("tripbus_sesiones_rastreo{estado=\"ACTIVO\"} 3.0")
                .contains("tripbus_sesiones_rastreo{estado=\"SIN_SENAL\"} 1.0")
                .contains("tripbus_sesiones_rastreo{estado=\"EN_PAUSA\"} 0.0")
                .contains("tripbus_sesiones_rastreo{estado=\"FINALIZADO\"} 0.0");
    }
}
