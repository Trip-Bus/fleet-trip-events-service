package com.tripbus.fleet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HT-08: retención de bus_positions. Después de detailDays se deja una posición por minuto por viaje; después
 * de keepDays se borran (la puntualidad queda en trip_punctuality). batchSize: filas por sentencia.
 */
@ConfigurationProperties(prefix = "tripbus.fleet.positions")
public record PositionRetentionProperties(Integer detailDays, Integer keepDays, Integer batchSize) {

    public PositionRetentionProperties {
        detailDays = detailDays != null ? detailDays : 7;
        keepDays = keepDays != null ? keepDays : 90;
        batchSize = batchSize != null ? batchSize : 5000;
        if (keepDays < detailDays) {
            throw new IllegalArgumentException("tripbus.fleet.positions.keep-days no puede ser menor que detail-days");
        }
    }
}
