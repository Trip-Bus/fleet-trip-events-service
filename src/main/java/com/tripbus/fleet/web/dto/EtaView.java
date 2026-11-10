package com.tripbus.fleet.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * US-180: hora estimada de llegada a cada parada. retrasoMinutos es positivo si va atrasado (nunca
 * menor a -5). En una parada alcanzada, estimada es null.
 */
public record EtaView(UUID itineraryId, long retrasoMinutos, Instant calculadaEn, List<Parada> paradas) {

    public record Parada(int orden, String nombre, Instant programada, Instant estimada, boolean alcanzada) {
    }
}
