package com.tripbus.fleet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "tripbus.fleet")
public record FleetProperties(
        String telemetryStream,
        String telemetryGroup,
        Duration maxPositionAge,
        String catalogUrl,
        boolean simulatorEnabled,
        Duration trackingWindowBefore,
        Duration trackingWindowAfter,
        Double maxAccuracyM,
        Duration signalTimeout) {

    public FleetProperties {
        // US-173: lecturas con precisión peor que esto (metros) se descartan.
        maxAccuracyM = maxAccuracyM != null ? maxAccuracyM : 100.0;
        // US-174: sin posiciones por más de esto, la sesión pasa a SIN_SENAL.
        signalTimeout = signalTimeout != null ? signalTimeout : Duration.ofSeconds(60);
        telemetryStream = telemetryStream != null ? telemetryStream : "tripbus:telemetry";
        telemetryGroup = telemetryGroup != null ? telemetryGroup : "fleet-telemetry";
        maxPositionAge = maxPositionAge != null ? maxPositionAge : Duration.ofMinutes(10);
        trackingWindowBefore = trackingWindowBefore != null ? trackingWindowBefore : Duration.ofMinutes(30);
        trackingWindowAfter = trackingWindowAfter != null ? trackingWindowAfter : Duration.ofMinutes(30);
    }
}
