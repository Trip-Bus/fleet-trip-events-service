package com.tripbus.fleet.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

/**
 * US-176: botón SOS (un toque). lat/lng los manda el celular; si el GPS no los tiene se usa la última
 * posición del viaje (un SOS nunca se rechaza por falta de coordenadas).
 */
public record SosRequest(
        @DecimalMin(value = "-90.0", message = "Latitud inválida") @DecimalMax(value = "90.0", message = "Latitud inválida")
        Double lat,

        @DecimalMin(value = "-180.0", message = "Longitud inválida") @DecimalMax(value = "180.0", message = "Longitud inválida")
        Double lng,

        @Size(max = 300, message = "El mensaje no puede superar 300 caracteres")
        String mensaje) {
}
