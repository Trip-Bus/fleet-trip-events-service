package com.tripbus.fleet.simulator;

import com.tripbus.fleet.domain.RouteStop;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GpsSimulatorTest {

    private final UUID route = UUID.randomUUID();
    private final List<RouteStop> stops = List.of(
            new RouteStop(route, 1, "Bogotá", 4.0, -74.0, 0),
            new RouteStop(route, 2, "Intermedia", 5.0, -74.0, 60),
            new RouteStop(route, 3, "Destino", 5.0, -75.0, 120));

    @Test
    void interpolaLaPosicionEntreParadasSegunElTiempo() {
        GpsSimulator.Point mitadPrimerTramo = GpsSimulator.interpolate(stops, 30);
        assertThat(mitadPrimerTramo.lat()).isCloseTo(4.5, within(1e-9));
        assertThat(mitadPrimerTramo.lng()).isCloseTo(-74.0, within(1e-9));
        assertThat(mitadPrimerTramo.bearing()).isCloseTo(0.0, within(0.5));

        GpsSimulator.Point segundoTramo = GpsSimulator.interpolate(stops, 90);
        assertThat(segundoTramo.lng()).isCloseTo(-74.5, within(1e-9));
        assertThat(segundoTramo.bearing()).isCloseTo(270.0, within(1.0));

        assertThat(GpsSimulator.interpolate(stops, 500).lng()).isEqualTo(-75.0);
    }

    @Test
    void calculaDistanciasRazonables() {
        // Un grado de latitud ≈ 111 km.
        assertThat(GpsSimulator.haversineKm(4, -74, 5, -74)).isCloseTo(111.2, within(0.5));
    }
}
