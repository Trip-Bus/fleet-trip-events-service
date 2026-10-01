package com.tripbus.fleet.web.dto;

import com.tripbus.fleet.domain.TripEventType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** US-046: novedad del viaje reportada por el conductor u operador. */
public record TripEventRequest(
        @NotNull(message = "El tipo de novedad es obligatorio (INICIO, LLEGADA_PARADA, RETRASO, DESVIO, FIN)")
        TripEventType tipo,

        @Size(max = 300, message = "La descripción no puede superar 300 caracteres")
        String descripcion,

        Map<String, Object> detalle) {
}
