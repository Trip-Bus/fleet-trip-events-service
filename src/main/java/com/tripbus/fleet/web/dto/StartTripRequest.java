package com.tripbus.fleet.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * US-171: checklist de salida (manifiestoRevisado, documentosBus, lucesYLlantas, botiquinYExtintor;
 * configurable en tripbus.fleet.checklist-items) y observación opcional.
 */
public record StartTripRequest(
        @NotNull(message = "El checklist es obligatorio")
        Map<String, Boolean> checklist,

        @Size(max = 300, message = "La observación no puede superar 300 caracteres")
        String observacion) {
}
