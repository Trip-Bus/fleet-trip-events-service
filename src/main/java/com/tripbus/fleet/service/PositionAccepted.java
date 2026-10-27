package com.tripbus.fleet.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento interno (Spring): una posición de un viaje en ventana activa quedó guardada. Lo escucha
 * TripAnalysisService después del commit (US-177/US-180), igual para el conductor y el simulador.
 */
public record PositionAccepted(UUID itineraryId, double lat, double lng, Instant timestamp) {
}
