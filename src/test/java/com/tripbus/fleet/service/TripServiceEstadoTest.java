package com.tripbus.fleet.service;

import com.tripbus.fleet.domain.FleetItinerary;
import com.tripbus.fleet.web.dto.PositionResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** El cálculo de estado que comparten bus-position (US-039) y mis viajes de hoy (US-170). */
class TripServiceEstadoTest {

    private final Instant now = Instant.parse("2026-10-22T15:00:00Z");

    private FleetItinerary viaje(Instant salida, Instant llegada) {
        return new FleetItinerary(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Bogotá", "Medellín", salida, llegada);
    }

    private PositionResponse posicion(Instant timestamp) {
        return new PositionResponse(UUID.randomUUID(), null, 5.0, -74.0, null, null, timestamp);
    }

    @Test
    void calculaElEstadoEnOrdenDePrioridad() {
        FleetItinerary enCurso = viaje(now.minus(1, ChronoUnit.HOURS), now.plus(5, ChronoUnit.HOURS));
        assertThat(TripService.estado(enCurso, posicion(now.minus(1, ChronoUnit.MINUTES)), now)).isEqualTo("EN_RUTA");
        assertThat(TripService.estado(enCurso, posicion(now.minus(6, ChronoUnit.MINUTES)), now)).isEqualTo("SIN_SENAL");
        assertThat(TripService.estado(enCurso, null, now)).isEqualTo("SIN_SENAL");

        FleetItinerary programado = viaje(now.plus(1, ChronoUnit.HOURS), now.plus(5, ChronoUnit.HOURS));
        assertThat(TripService.estado(programado, null, now)).isEqualTo("PROGRAMADO");

        FleetItinerary terminado = viaje(now.minus(5, ChronoUnit.HOURS), now.minus(1, ChronoUnit.MINUTES));
        assertThat(TripService.estado(terminado, posicion(now), now)).isEqualTo("FINALIZADO");

        enCurso.cancelar();
        assertThat(TripService.estado(enCurso, posicion(now), now)).isEqualTo("CANCELADO");
    }
}
