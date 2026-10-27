package com.tripbus.fleet.web.dto;

import java.time.Instant;
import java.util.UUID;

/** US-176: alerta de una novedad grave o un SOS (para el operador de la empresa). */
public record TripAlertResponse(
        UUID alertId,
        UUID itineraryId,
        UUID companyId,
        UUID busId,
        String tipo,
        String gravedad,
        Double lat,
        Double lng,
        String mensaje,
        Boolean puedeContinuar,
        String estado,
        Instant creadoEn,
        Instant resueltoEn,
        String notaResolucion) {
}
