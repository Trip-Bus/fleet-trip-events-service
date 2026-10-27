package com.tripbus.fleet.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TripEventTypeTest {

    @Test
    void laGravedadDeCadaNovedad() {
        assertThat(TripEventType.SOS.gravedad()).isEqualTo(TripEventType.Gravedad.CRITICA);
        assertThat(TripEventType.ACCIDENTE.gravedad()).isEqualTo(TripEventType.Gravedad.CRITICA);
        assertThat(TripEventType.AVERIA.gravedad()).isEqualTo(TripEventType.Gravedad.ALTA);
        assertThat(TripEventType.VIA_CERRADA.gravedad()).isEqualTo(TripEventType.Gravedad.ALTA);
        assertThat(TripEventType.RETRASO.gravedad()).isEqualTo(TripEventType.Gravedad.NORMAL);
        assertThat(TripEventType.INICIO.gravedad()).isEqualTo(TripEventType.Gravedad.NORMAL);

        assertThat(TripEventType.AVERIA.generaAlerta()).isTrue();
        assertThat(TripEventType.FIN.generaAlerta()).isFalse();
    }
}
