package com.tripbus.fleet.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** US-173: resultado de una lectura. Si no se aceptó, motivo dice por qué (no es un error del envío). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocationResult(Instant timestamp, boolean aceptada, String motivo, String eventId) {
}
