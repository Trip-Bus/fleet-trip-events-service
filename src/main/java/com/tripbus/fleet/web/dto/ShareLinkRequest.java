package com.tripbus.fleet.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** US-181: POST /share-links. */
public record ShareLinkRequest(@NotNull(message = "itineraryId es obligatorio") UUID itineraryId) {
}
