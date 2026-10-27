package com.tripbus.fleet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * US-177/US-180: umbrales de la detección automática y de la publicación del ETA (mismo prefijo
 * tripbus.fleet que {@link FleetProperties}).
 */
@ConfigurationProperties(prefix = "tripbus.fleet")
public record DetectionProperties(
        Double stopRadiusM,
        Double stopRadiusEndsM,
        Integer delayThresholdMin,
        Double deviationM,
        Duration etaPublishInterval) {

    public DetectionProperties {
        // Llegada a parada: a menos de esto de la siguiente pendiente (las del extremo, más amplio).
        stopRadiusM = stopRadiusM != null ? stopRadiusM : 150.0;
        stopRadiusEndsM = stopRadiusEndsM != null ? stopRadiusEndsM : 300.0;
        // RETRASO automático desde estos minutos (y de nuevo cada 15 más).
        delayThresholdMin = delayThresholdMin != null ? delayThresholdMin : 10;
        // DESVIO: a más de esto de la ruta en 3 lecturas seguidas.
        deviationM = deviationM != null ? deviationM : 1000.0;
        // EtaUpdated como máximo una vez por este intervalo por viaje.
        etaPublishInterval = etaPublishInterval != null ? etaPublishInterval : Duration.ofSeconds(30);
    }
}
