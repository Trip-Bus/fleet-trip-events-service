package com.tripbus.fleet.service;

import com.tripbus.fleet.common.geo.Geo;
import com.tripbus.fleet.domain.RouteStop;
import com.tripbus.fleet.web.dto.EtaView;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * US-180: retraso actual y hora estimada de llegada a cada parada. Cálculo puro (sin base de datos ni
 * reloj): recibe las paradas en orden, el avance real y la posición en el instante "ahora".
 *
 * <ul>
 *   <li>Hora programada de una parada = fechaSalida + minutos_desde_salida.</li>
 *   <li>Retraso: la posición se proyecta sobre el tramo entre la última parada alcanzada y la siguiente
 *   (si el bus ya está más adelante, sobre el tramo más cercano a partir de ahí); la fracción recorrida
 *   da la hora programada de ese punto. retraso = ahora − esa hora, nunca menor a −5 min.</li>
 *   <li>Siguiente parada: con velocidad promedio mayor a 10 km/h, ahora + distancia restante / velocidad;
 *   si no, hora programada + retraso.</li>
 *   <li>Demás paradas: hora programada + retraso, nunca antes de ahora.</li>
 * </ul>
 */
public final class EtaCalculator {

    static final Duration ADELANTO_MAXIMO = Duration.ofMinutes(-5);
    static final double VELOCIDAD_MINIMA_KMH = 10;

    /** Retraso (ya limitado a −5 min), en minutos redondeados, y la ETA de cada parada. */
    public record Resultado(Duration retraso, long retrasoMinutos, List<EtaView.Parada> paradas,
                            boolean todasAlcanzadas) {
    }

    private EtaCalculator() {
    }

    /**
     * @param stops             paradas de la ruta ordenadas por orden (al menos 2)
     * @param ultimaParadaReal  orden de la última parada alcanzada (0 = ninguna)
     * @param velocidadKmh      velocidad promedio reciente, o null si no hay lecturas suficientes
     */
    public static Resultado calcular(List<RouteStop> stops, Instant salida, int ultimaParadaReal, Geo.Punto posicion,
                                     Instant ahora, Double velocidadKmh) {
        int ultimaAlcanzada = -1;
        for (int i = 0; i < stops.size(); i++) {
            if (stops.get(i).getOrden() <= ultimaParadaReal) {
                ultimaAlcanzada = i;
            }
        }
        boolean todasAlcanzadas = ultimaAlcanzada == stops.size() - 1;

        Instant horaDelPunto;
        int siguiente;
        if (todasAlcanzadas) {
            horaDelPunto = programada(stops.get(stops.size() - 1), salida);
            siguiente = stops.size();
        } else {
            int tramo = tramoMasCercano(stops, Math.max(ultimaAlcanzada, 0), posicion);
            RouteStop a = stops.get(tramo);
            RouteStop b = stops.get(tramo + 1);
            double f = Geo.fraccionEnTramo(posicion, punto(a), punto(b));
            Duration duracionTramo = Duration.between(programada(a, salida), programada(b, salida));
            horaDelPunto = programada(a, salida).plusMillis(Math.round(duracionTramo.toMillis() * f));
            // Antes de llegar al origen (no alcanzó ninguna y no ha avanzado), la siguiente es la primera.
            siguiente = ultimaAlcanzada < 0 && f <= 0 ? 0 : tramo + 1;
        }
        Duration retraso = Duration.between(horaDelPunto, ahora);
        if (retraso.compareTo(ADELANTO_MAXIMO) < 0) {
            retraso = ADELANTO_MAXIMO;
        }

        List<EtaView.Parada> paradas = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            RouteStop stop = stops.get(i);
            Instant prog = programada(stop, salida);
            boolean alcanzada = i <= ultimaAlcanzada;
            Instant estimada = alcanzada ? null
                    : estimada(stop, prog.plus(retraso), i == siguiente ? velocidadKmh : null, posicion, ahora);
            paradas.add(new EtaView.Parada(stop.getOrden(), stop.getNombre(), prog, estimada, alcanzada));
        }
        return new Resultado(retraso, Math.round(retraso.toSeconds() / 60.0), paradas, todasAlcanzadas);
    }

    /**
     * ETA de una parada pendiente: con velocidad (solo la siguiente) mayor a 10 km/h, ahora + distancia /
     * velocidad; si no, programada + retraso, nunca antes de ahora.
     */
    private static Instant estimada(RouteStop stop, Instant programadaConRetraso, Double velocidadKmh,
                                    Geo.Punto posicion, Instant ahora) {
        if (velocidadKmh != null && velocidadKmh > VELOCIDAD_MINIMA_KMH) {
            double km = Geo.distanciaM(posicion, punto(stop)) / 1000;
            return ahora.plusSeconds(Math.round(km / velocidadKmh * 3600));
        }
        return max(ahora, programadaConRetraso);
    }

    /** Tramo (índice de su parada inicial) más cercano a la posición, desde el tramo desde. */
    private static int tramoMasCercano(List<RouteStop> stops, int desde, Geo.Punto posicion) {
        int mejor = desde;
        double minima = Double.MAX_VALUE;
        for (int i = desde; i < stops.size() - 1; i++) {
            double d = Geo.distanciaATramoM(posicion, punto(stops.get(i)), punto(stops.get(i + 1)));
            if (d < minima) {
                minima = d;
                mejor = i;
            }
        }
        return mejor;
    }

    static Instant programada(RouteStop stop, Instant salida) {
        return salida.plusSeconds(stop.getMinutosDesdeSalida() * 60L);
    }

    static Geo.Punto punto(RouteStop stop) {
        return new Geo.Punto(stop.getLat(), stop.getLng());
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
