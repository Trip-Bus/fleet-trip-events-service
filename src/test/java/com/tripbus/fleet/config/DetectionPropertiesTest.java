package com.tripbus.fleet.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DetectionPropertiesTest {

    @Test
    void sinConfiguracionUsaLosValoresPorDefecto() {
        DetectionProperties p = new DetectionProperties(null, null, null, null, null);

        assertThat(p.stopRadiusM()).isEqualTo(150.0);
        assertThat(p.stopRadiusEndsM()).isEqualTo(300.0);
        assertThat(p.delayThresholdMin()).isEqualTo(10);
        assertThat(p.deviationM()).isEqualTo(1000.0);
        assertThat(p.etaPublishInterval()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void respetaLaConfiguracion() {
        DetectionProperties p = new DetectionProperties(100.0, 250.0, 5, 500.0, Duration.ofSeconds(10));

        assertThat(p.stopRadiusM()).isEqualTo(100.0);
        assertThat(p.stopRadiusEndsM()).isEqualTo(250.0);
        assertThat(p.delayThresholdMin()).isEqualTo(5);
        assertThat(p.deviationM()).isEqualTo(500.0);
        assertThat(p.etaPublishInterval()).isEqualTo(Duration.ofSeconds(10));
    }
}
