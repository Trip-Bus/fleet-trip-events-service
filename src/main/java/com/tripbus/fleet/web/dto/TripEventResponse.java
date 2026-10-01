package com.tripbus.fleet.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record TripEventResponse(UUID tripEventId, UUID itineraryId, String tipo, String descripcion, JsonNode detalle,
                                String origen, Instant timestamp) {
}
