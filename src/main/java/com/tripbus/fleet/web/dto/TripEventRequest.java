package com.tripbus.fleet.web.dto;

import com.tripbus.fleet.domain.TripEventType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * US-046: novedad del viaje reportada por el conductor u operador. US-176: lat/lng opcionales (si no
 * vienen se usa la última posición del viaje) y puedeContinuar=false publica TripInterrupted.
 */
public record TripEventRequest(
        @NotNull(message = "El tipo de novedad es obligatorio (INICIO, LLEGADA_PARADA, RETRASO, DESVIO, FIN, "
                + "AVERIA, ACCIDENTE, VIA_CERRADA, SOS)")
        TripEventType tipo,

        @Size(max = 300, message = "La descripción no puede superar 300 caracteres")
        String descripcion,

        Map<String, Object> detalle,

        @DecimalMin(value = "-90.0", message = "Latitud inválida") @DecimalMax(value = "90.0", message = "Latitud inválida")
        Double lat,

        @DecimalMin(value = "-180.0", message = "Longitud inválida") @DecimalMax(value = "180.0", message = "Longitud inválida")
        Double lng,

        Boolean puedeContinuar) {
}
