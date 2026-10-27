package com.tripbus.fleet.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FleetPropertiesTest {

    @Test
    void sinConfiguracionUsaLosValoresPorDefecto() {
        FleetProperties p = new FleetProperties(null, null, null, null, false, null, null, null, null, null);

        assertThat(p.telemetryStream()).isEqualTo("tripbus:telemetry");
        assertThat(p.telemetryGroup()).isEqualTo("fleet-telemetry");
        assertThat(p.maxPositionAge()).isEqualTo(Duration.ofMinutes(10));
        assertThat(p.trackingWindowBefore()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.trackingWindowAfter()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.maxAccuracyM()).isEqualTo(100.0);
        assertThat(p.signalTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.checklistItems()).isEqualTo(FleetProperties.CHECKLIST_POR_DEFECTO);
        assertThat(new FleetProperties(null, null, null, null, false, null, null, null, null, List.of()).checklistItems())
                .isEqualTo(FleetProperties.CHECKLIST_POR_DEFECTO);
    }

    @Test
    void respetaLaConfiguracion() {
        FleetProperties p = new FleetProperties("s", "g", Duration.ofMinutes(5), "http://catalog", true,
                Duration.ofMinutes(10), Duration.ofMinutes(15), 50.0, Duration.ofSeconds(30), List.of("documentosBus"));

        assertThat(p.telemetryStream()).isEqualTo("s");
        assertThat(p.maxAccuracyM()).isEqualTo(50.0);
        assertThat(p.signalTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.checklistItems()).containsExactly("documentosBus");
    }
}
