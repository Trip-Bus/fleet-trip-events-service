package com.tripbus.fleet.common.geo;

import java.util.List;

/**
 * Cálculos geográficos simples (simulador GPS, filtros de la ubicación del conductor y, US-177/US-180,
 * detección de paradas, desvíos y ETA). La ruta es la polilínea de sus paradas en orden: tramos en
 * línea recta entre paradas, no el trazado de la vía.
 */
public final class Geo {

    private static final double RADIO_TIERRA_KM = 6371;
    private static final double RADIO_TIERRA_M = RADIO_TIERRA_KM * 1000;

    /** Un punto en grados decimales. */
    public record Punto(double lat, double lng) {
    }

    private Geo() {
    }

    /** Distancia en km entre dos puntos (fórmula del haversine). */
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * RADIO_TIERRA_KM * Math.asin(Math.sqrt(h));
    }

    /** Distancia en metros entre dos puntos (haversine). */
    public static double distanciaM(Punto a, Punto b) {
        return haversineKm(a.lat(), a.lng(), b.lat(), b.lng()) * 1000;
    }

    /**
     * Qué fracción del tramo a→b lleva el punto p: su proyección sobre el segmento, entre 0 (en a) y
     * 1 (en b). Se calcula en un plano local (equirectangular), suficiente para tramos entre paradas.
     */
    public static double fraccionEnTramo(Punto p, Punto a, Punto b) {
        double[] ab = plano(b, a, a, b);
        double largo2 = ab[0] * ab[0] + ab[1] * ab[1];
        if (largo2 <= 0) {
            return 0;
        }
        double[] ap = plano(p, a, a, b);
        double f = (ap[0] * ab[0] + ap[1] * ab[1]) / largo2;
        return Math.max(0, Math.min(1, f));
    }

    /** Distancia en metros del punto p al segmento a→b (al punto más cercano del tramo). */
    public static double distanciaATramoM(Punto p, Punto a, Punto b) {
        double f = fraccionEnTramo(p, a, b);
        Punto cercano = new Punto(a.lat() + f * (b.lat() - a.lat()), a.lng() + f * (b.lng() - a.lng()));
        return distanciaM(p, cercano);
    }

    /** Distancia en metros del punto a la polilínea (el tramo más cercano). */
    public static double distanciaAPolilineaM(Punto p, List<Punto> puntos) {
        if (puntos.size() == 1) {
            return distanciaM(p, puntos.get(0));
        }
        double minima = Double.MAX_VALUE;
        for (int i = 0; i < puntos.size() - 1; i++) {
            minima = Math.min(minima, distanciaATramoM(p, puntos.get(i), puntos.get(i + 1)));
        }
        return minima;
    }

    /** Coordenadas (x, y) en metros de p respecto al origen, en el plano local del tramo a→b. */
    private static double[] plano(Punto p, Punto origen, Punto a, Punto b) {
        double cosLat = Math.cos(Math.toRadians((a.lat() + b.lat()) / 2));
        return new double[]{
                Math.toRadians(p.lng() - origen.lng()) * cosLat * RADIO_TIERRA_M,
                Math.toRadians(p.lat() - origen.lat()) * RADIO_TIERRA_M};
    }
}
