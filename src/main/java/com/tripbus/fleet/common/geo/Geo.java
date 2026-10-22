package com.tripbus.fleet.common.geo;

/** Cálculos geográficos simples (simulador GPS y filtros de la ubicación del conductor). */
public final class Geo {

    private static final double RADIO_TIERRA_KM = 6371;

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
}
