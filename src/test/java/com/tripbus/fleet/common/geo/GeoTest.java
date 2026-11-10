package com.tripbus.fleet.common.geo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GeoTest {

    private final Geo.Punto a = new Geo.Punto(5.0, -74.0);
    private final Geo.Punto b = new Geo.Punto(5.0, -73.0);

    @Test
    void distanciaEntreDosPuntos() {
        // Un grado de latitud ≈ 111,19 km.
        assertThat(Geo.distanciaM(new Geo.Punto(4, -74), new Geo.Punto(5, -74))).isCloseTo(111_195, within(10.0));
        assertThat(Geo.distanciaM(a, a)).isZero();
    }

    @Test
    void fraccionRecorridaDelTramo() {
        assertThat(Geo.fraccionEnTramo(a, a, b)).isZero();
        assertThat(Geo.fraccionEnTramo(new Geo.Punto(5.0, -73.5), a, b)).isCloseTo(0.5, within(0.001));
        // Se proyecta: un punto al lado de la mitad del tramo también va en la mitad.
        assertThat(Geo.fraccionEnTramo(new Geo.Punto(5.02, -73.75), a, b)).isCloseTo(0.25, within(0.001));
        // Fuera del tramo se limita a sus extremos.
        assertThat(Geo.fraccionEnTramo(new Geo.Punto(5.0, -74.5), a, b)).isZero();
        assertThat(Geo.fraccionEnTramo(new Geo.Punto(5.0, -72.0), a, b)).isEqualTo(1.0);
        // Tramo de largo cero.
        assertThat(Geo.fraccionEnTramo(b, a, a)).isZero();
    }

    @Test
    void distanciaDeUnPuntoAlTramoYALaRuta() {
        // 0,01° de latitud al lado de un tramo este-oeste ≈ 1112 m.
        Geo.Punto alLado = new Geo.Punto(5.01, -73.5);
        assertThat(Geo.distanciaATramoM(alLado, a, b)).isCloseTo(1112, within(5.0));
        // Pasado el extremo, la distancia es al extremo.
        assertThat(Geo.distanciaATramoM(new Geo.Punto(5.0, -72.99), a, b))
                .isCloseTo(Geo.distanciaM(new Geo.Punto(5.0, -72.99), b), within(0.5));

        Geo.Punto c = new Geo.Punto(6.0, -73.0);
        // El tramo más cercano de la polilínea a→b→c es b→c.
        assertThat(Geo.distanciaAPolilineaM(new Geo.Punto(5.5, -72.99), List.of(a, b, c)))
                .isCloseTo(Geo.distanciaATramoM(new Geo.Punto(5.5, -72.99), b, c), within(0.5));
        assertThat(Geo.distanciaAPolilineaM(alLado, List.of(a))).isEqualTo(Geo.distanciaM(alLado, a));
    }
}
