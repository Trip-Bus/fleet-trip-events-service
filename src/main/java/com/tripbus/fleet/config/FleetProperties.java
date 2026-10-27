package com.tripbus.fleet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

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
        Duration signalTimeout,
        List<String> checklistItems) {

    /** US-171: ítems obligatorios del checklist de salida si no se configuran otros. */
    public static final List<String> CHECKLIST_POR_DEFECTO =
            List.of("manifiestoRevisado", "documentosBus", "lucesYLlantas", "botiquinYExtintor");

    public FleetProperties {
        // US-171: todos deben venir en true para iniciar el viaje.
        checklistItems = checklistItems != null && !checklistItems.isEmpty()
                ? List.copyOf(checklistItems) : CHECKLIST_POR_DEFECTO;
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
