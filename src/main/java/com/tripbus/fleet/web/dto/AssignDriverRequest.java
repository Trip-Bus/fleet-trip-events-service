package com.tripbus.fleet.web.dto;

import com.tripbus.fleet.domain.DriverRole;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** US-121: conductor que se asigna al viaje. Sin rol se asigna como PRINCIPAL. */
public record AssignDriverRequest(
        @NotNull(message = "conductorId es obligatorio")
        UUID conductorId,

        DriverRole rol) {
}
