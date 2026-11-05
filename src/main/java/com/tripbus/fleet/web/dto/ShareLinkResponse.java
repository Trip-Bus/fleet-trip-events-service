package com.tripbus.fleet.web.dto;

import java.time.Instant;
import java.util.UUID;

/** US-181: enlace creado (el código se abre en GET /share/{codigo}). */
public record ShareLinkResponse(String codigo, UUID itineraryId, Instant venceEn) {
}
