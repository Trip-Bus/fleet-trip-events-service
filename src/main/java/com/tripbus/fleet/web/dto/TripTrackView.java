package com.tripbus.fleet.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * US-182: recorrido y puntualidad de un viaje terminado (GET /itineraries/{id}/track, público). El recorrido
 * tiene como máximo un punto por minuto. Nunca lleva datos del conductor ni de los pasajeros.
 */
public record TripTrackView(UUID itineraryId, String origen, String destino, List<Punto> recorrido,
                            List<Parada> paradas, Puntualidad puntualidad) {

    public record Punto(double lat, double lng, Instant timestamp) {
    }

    /** real es null si no se registró la llegada; aTiempo: llegó con 5 min de retraso o menos. */
    public record Parada(int orden, String nombre, double lat, double lng, Instant programada, Instant real,
                         boolean aTiempo) {
    }

    public record Puntualidad(Instant salidaProgramada, Instant salidaReal, Instant llegadaProgramada,
                              Instant llegadaReal, Integer minutosRetrasoSalida, Integer minutosRetrasoLlegada,
                              int paradasATiempo, int paradasTotal) {
    }
}
