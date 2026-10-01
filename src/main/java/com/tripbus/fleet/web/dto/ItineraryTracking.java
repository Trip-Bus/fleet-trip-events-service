package com.tripbus.fleet.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** US-039: dónde va mi bus (última posición conocida + ruta para dibujar el mapa). */
public record ItineraryTracking(
        UUID itineraryId,
        UUID busId,
        String origen,
        String destino,
        Instant fechaSalida,
        Instant llegadaProgramada,
        String estado,
        PositionResponse posicion,
        List<StopView> paradas) {
}
