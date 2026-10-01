package com.tripbus.fleet.web.dto;

import java.time.Instant;
import java.util.UUID;

public record PositionResponse(UUID busId, UUID itineraryId, double lat, double lng, Double velocidad, Double rumbo,
                               Instant timestamp) {
}
