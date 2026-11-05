package com.tripbus.fleet.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * US-181: lo que ve el familiar al abrir el enlace (GET /share/{codigo}). Nunca datos del pasajero: el
 * seguimiento sigue por los endpoints públicos de siempre con el itineraryId.
 */
public record SharedTripView(UUID itineraryId, String origen, String destino, Instant fechaSalida, Instant venceEn) {
}
