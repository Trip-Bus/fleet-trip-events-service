package com.tripbus.fleet.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * US-039: dónde va mi bus (última posición conocida + ruta para dibujar el mapa). US-174:
 * estadoRastreo (ACTIVO, EN_PAUSA, SIN_SENAL, FINALIZADO; null si nunca se encendió) y
 * ultimaPosicionEn de la sesión de rastreo.
 */
public record ItineraryTracking(
        UUID itineraryId,
        UUID busId,
        String origen,
        String destino,
        Instant fechaSalida,
        Instant llegadaProgramada,
        String estado,
        PositionResponse posicion,
        List<StopView> paradas,
        String estadoRastreo,
        Instant ultimaPosicionEn) {
}
