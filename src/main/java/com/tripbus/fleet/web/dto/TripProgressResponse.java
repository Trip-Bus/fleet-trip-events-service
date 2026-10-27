package com.tripbus.fleet.web.dto;

import java.time.Instant;
import java.util.UUID;

/** US-171: el viaje tras iniciarlo o finalizarlo, con el estado de su rastreo. */
public record TripProgressResponse(UUID itineraryId, Instant iniciadoEn, Instant finalizadoEn, String estadoRastreo) {
}
