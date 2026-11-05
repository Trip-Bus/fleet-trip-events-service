package com.tripbus.fleet.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PositionRetentionPropertiesTest {

    @Test
    void valoresPorDefecto() {
        PositionRetentionProperties properties = new PositionRetentionProperties(null, null, null);

        assertThat(properties.detailDays()).isEqualTo(7);
        assertThat(properties.keepDays()).isEqualTo(90);
        assertThat(properties.batchSize()).isEqualTo(5000);
    }

    @Test
    void noSePuedeBorrarAntesDeResumir() {
        assertThatThrownBy(() -> new PositionRetentionProperties(30, 10, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
