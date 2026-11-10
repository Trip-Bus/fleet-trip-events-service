package com.tripbus.fleet.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** US-173: lectura de ubicación del celular del conductor. Sin busId ni itineraryId: los resuelve fleet. */
public record DriverLocationRequest(
        @NotNull(message = "lat es obligatoria")
        @DecimalMin(value = "-90.0", message = "Latitud inválida") @DecimalMax(value = "90.0", message = "Latitud inválida")
        Double lat,

        @NotNull(message = "lng es obligatoria")
        @DecimalMin(value = "-180.0", message = "Longitud inválida") @DecimalMax(value = "180.0", message = "Longitud inválida")
        Double lng,

        @NotNull(message = "precision es obligatoria (metros)")
        @DecimalMin(value = "0.0", message = "Precisión inválida")
        Double precision,

        @DecimalMin(value = "0.0", message = "Velocidad inválida") @DecimalMax(value = "200.0", message = "Velocidad inválida")
        Double velocidad,

        @DecimalMin(value = "0.0", message = "Rumbo inválido") @DecimalMax(value = "360.0", message = "Rumbo inválido")
        Double rumbo,

        Instant timestamp) {
}
