package com.tripbus.fleet.web.dto;

import java.time.Instant;
import java.util.UUID;

/** US-121: conductor asignado a un viaje. */
public record DriverAssignmentResponse(UUID itineraryId, UUID conductorId, String rol, UUID asignadoPor,
                                       Instant asignadoEn) {
}
