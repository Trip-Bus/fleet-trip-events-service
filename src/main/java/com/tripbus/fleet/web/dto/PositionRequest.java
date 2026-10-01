package com.tripbus.fleet.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/** US-038: posición GPS reportada por el bus (en el MVP, por el simulador). */
public record PositionRequest(
        @NotNull(message = "busId es obligatorio")
        UUID busId,

        UUID itineraryId,

        @NotNull @DecimalMin(value = "-90.0", message = "Latitud inválida") @DecimalMax(value = "90.0", message = "Latitud inválida")
        Double lat,

        @NotNull @DecimalMin(value = "-180.0", message = "Longitud inválida") @DecimalMax(value = "180.0", message = "Longitud inválida")
        Double lng,

        @DecimalMin(value = "0.0", message = "Velocidad inválida") @DecimalMax(value = "200.0", message = "Velocidad inválida")
        Double velocidad,

        @DecimalMin(value = "0.0", message = "Rumbo inválido") @DecimalMax(value = "360.0", message = "Rumbo inválido")
        Double rumbo,

        Instant timestamp) {
}
