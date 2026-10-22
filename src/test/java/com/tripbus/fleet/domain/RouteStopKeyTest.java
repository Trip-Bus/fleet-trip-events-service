package com.tripbus.fleet.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RouteStopKeyTest {

    @Test
    void laLlaveCompuestaComparaRutaYOrden() {
        UUID ruta = UUID.randomUUID();
        RouteStop.Key llave = new RouteStop.Key(ruta, 1);

        assertThat(llave).isEqualTo(new RouteStop.Key(ruta, 1)).hasSameHashCodeAs(new RouteStop.Key(ruta, 1));
        assertThat(llave).isNotEqualTo(new RouteStop.Key(ruta, 2));
        assertThat(llave).isNotEqualTo(new RouteStop.Key(UUID.randomUUID(), 1));
        // equals con un objeto que no es una Key (el parámetro de equals es Object).
        Object otroTipo = Integer.valueOf(1);
        assertThat(llave.equals(otroTipo)).isFalse();
        assertThat(new RouteStop.Key()).isEqualTo(new RouteStop.Key());
    }
}
