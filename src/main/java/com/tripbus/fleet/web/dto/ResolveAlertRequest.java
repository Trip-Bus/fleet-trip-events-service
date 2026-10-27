package com.tripbus.fleet.web.dto;

import jakarta.validation.constraints.Size;

/** US-176: nota del operador al resolver una alerta. */
public record ResolveAlertRequest(
        @Size(max = 300, message = "La nota no puede superar 300 caracteres")
        String nota) {
}
