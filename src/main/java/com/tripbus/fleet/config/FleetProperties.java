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
        String telemetryDlqStream,
        Duration telemetryRetryIdle,
        int telemetryMaxDeliveries) {

    public FleetProperties {
        telemetryStream = telemetryStream != null ? telemetryStream : "tripbus:telemetry";
        telemetryGroup = telemetryGroup != null ? telemetryGroup : "fleet-telemetry";
        maxPositionAge = maxPositionAge != null ? maxPositionAge : Duration.ofMinutes(10);
        trackingWindowBefore = trackingWindowBefore != null ? trackingWindowBefore : Duration.ofMinutes(30);
        trackingWindowAfter = trackingWindowAfter != null ? trackingWindowAfter : Duration.ofMinutes(30);
        telemetryDlqStream = telemetryDlqStream != null ? telemetryDlqStream : "tripbus:telemetry:dlq";
        telemetryRetryIdle = telemetryRetryIdle != null ? telemetryRetryIdle : Duration.ofSeconds(30);
        telemetryMaxDeliveries = telemetryMaxDeliveries > 0 ? telemetryMaxDeliveries : 5;
    }
}
