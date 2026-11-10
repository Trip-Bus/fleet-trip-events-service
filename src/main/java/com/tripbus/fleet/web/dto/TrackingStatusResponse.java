package com.tripbus.fleet.web.dto;

import java.time.Instant;
import java.util.UUID;

/** US-174: estado del rastreo del viaje del conductor. */
public record TrackingStatusResponse(UUID itineraryId, UUID busId, String estado, Instant ultimaPosicionEn) {
}
