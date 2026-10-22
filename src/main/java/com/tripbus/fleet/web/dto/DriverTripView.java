package com.tripbus.fleet.web.dto;

import java.time.Instant;
import java.util.UUID;

/** US-170: un viaje asignado al conductor, con su ventana de rastreo. */
public record DriverTripView(
        UUID itineraryId,
        UUID busId,
        String origen,
        String destino,
        Instant fechaSalida,
        Instant fechaLlegada,
        String rol,
        String estado,
        Instant ventanaInicio,
        Instant ventanaFin,
        boolean puedeCompartirUbicacion) {
}
