package com.tripbus.fleet.service;

import com.tripbus.fleet.common.geo.Geo;
import com.tripbus.fleet.domain.RouteStop;
import com.tripbus.fleet.web.dto.EtaView;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** US-180 con una ruta real: Bogotá (0 min) → Chocontá (80 min) → Tunja (150 min), salida fija. */
class EtaCalculatorTest {

    private static final Instant SALIDA = Instant.parse("2026-10-26T12:00:00Z");

    private final UUID ruta = UUID.randomUUID();
    private final RouteStop bogota = new RouteStop(ruta, 1, "Bogotá", 4.6584, -74.1030, 0);
    private final RouteStop choconta = new RouteStop(ruta, 2, "Chocontá", 5.1447, -73.6858, 80);
    private final RouteStop tunja = new RouteStop(ruta, 3, "Tunja", 5.5446, -73.3573, 150);
    private final List<RouteStop> stops = List.of(bogota, choconta, tunja);

    /** La mitad del tramo Bogotá → Chocontá (hora programada de ese punto: salida + 40 min). */
    private final Geo.Punto mitadPrimerTramo = new Geo.Punto((4.6584 + 5.1447) / 2, (-74.1030 + -73.6858) / 2);

    private static Instant en(long minutos) {
        return SALIDA.plus(Duration.ofMinutes(minutos));
    }

    @Test
    void aTiempoLasEtaSonLasHorasProgramadas() {
        EtaCalculator.Resultado r = EtaCalculator.calcular(stops, SALIDA, 1, mitadPrimerTramo, en(40), null);

        assertThat(r.retrasoMinutos()).isZero();
        assertThat(r.todasAlcanzadas()).isFalse();
        EtaView.Parada b = r.paradas().get(0);
        assertThat(b.alcanzada()).isTrue();
        assertThat(b.estimada()).isNull();
        assertThat(b.programada()).isEqualTo(SALIDA);
        assertThat(r.paradas().get(1).programada()).isEqualTo(en(80));
        assertThat(Duration.between(en(80), r.paradas().get(1).estimada()).abs()).isLessThan(Duration.ofSeconds(30));
        assertThat(Duration.between(en(150), r.paradas().get(2).estimada()).abs()).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void conRetrasoLaSiguienteUsaLaVelocidadYLasDemasElRetraso() {
        EtaCalculator.Resultado r = EtaCalculator.calcular(stops, SALIDA, 1, mitadPrimerTramo, en(55), 60.0);

        assertThat(r.retrasoMinutos()).isEqualTo(15);
        double km = Geo.distanciaM(mitadPrimerTramo, new Geo.Punto(5.1447, -73.6858)) / 1000;
        Instant porVelocidad = en(55).plusSeconds(Math.round(km / 60.0 * 3600));
        assertThat(r.paradas().get(1).estimada()).isEqualTo(porVelocidad);
        assertThat(Duration.between(en(165), r.paradas().get(2).estimada()).abs()).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void despacioLaSiguienteTambienUsaElRetraso() {
        EtaCalculator.Resultado r = EtaCalculator.calcular(stops, SALIDA, 1, mitadPrimerTramo, en(55), 8.0);

        assertThat(Duration.between(en(95), r.paradas().get(1).estimada()).abs()).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void adelantadoNoSeMuestraMenosDeCincoMinutosYNuncaAntesDeAhora() {
        EtaCalculator.Resultado r = EtaCalculator.calcular(stops, SALIDA, 1, mitadPrimerTramo, en(20), null);

        assertThat(r.retraso()).isEqualTo(Duration.ofMinutes(-5));
        assertThat(r.retrasoMinutos()).isEqualTo(-5);
        assertThat(r.paradas().get(1).estimada()).isEqualTo(en(75));
        assertThat(r.paradas().get(2).estimada()).isEqualTo(en(145));
    }

    @Test
    void antesDeSalirLaSiguienteEsElOrigen() {
        Geo.Punto enBogota = new Geo.Punto(4.6584, -74.1030);
        EtaCalculator.Resultado r = EtaCalculator.calcular(stops, SALIDA, 0, enBogota, en(-10), 50.0);

        assertThat(r.paradas()).noneMatch(EtaView.Parada::alcanzada);
        // Ya está en el origen: por velocidad, la ETA del origen es "ahora".
        assertThat(r.paradas().get(0).estimada()).isEqualTo(en(-10));
        assertThat(r.paradas().get(2).estimada()).isEqualTo(en(145));
    }

    @Test
    void siYaPasoLaSiguienteSeProyectaSobreElTramoMasCercano() {
        Geo.Punto mitadSegundoTramo = new Geo.Punto((5.1447 + 5.5446) / 2, (-73.6858 + -73.3573) / 2);
        // Solo se detectó Bogotá, pero el bus va por la mitad de Chocontá → Tunja (hora programada: 115 min).
        EtaCalculator.Resultado r = EtaCalculator.calcular(stops, SALIDA, 1, mitadSegundoTramo, en(125), null);

        assertThat(r.retrasoMinutos()).isCloseTo(10, within(1L));
        assertThat(r.paradas().get(1).alcanzada()).isFalse();
    }

    @Test
    void conTodasLasParadasAlcanzadasNoHayEstimadas() {
        Geo.Punto enTunja = new Geo.Punto(5.5446, -73.3573);
        EtaCalculator.Resultado r = EtaCalculator.calcular(stops, SALIDA, 3, enTunja, en(160), null);

        assertThat(r.todasAlcanzadas()).isTrue();
        assertThat(r.retrasoMinutos()).isEqualTo(10);
        assertThat(r.paradas()).allMatch(p -> p.alcanzada() && p.estimada() == null);
    }
}
