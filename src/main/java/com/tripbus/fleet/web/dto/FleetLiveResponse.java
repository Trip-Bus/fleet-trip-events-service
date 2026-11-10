package com.tripbus.fleet.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * US-178: GET /fleet/live, el mapa de la flota del operador. Cada viaje está ahora dentro de su
 * ventana de rastreo y no está cancelado; posición y ETA salen de Redis (null si aún no hay).
 */
public record FleetLiveResponse(Instant generadoEn, Resumen resumen, List<Viaje> viajes) {

    /** enRuta: iniciados y no finalizados; retrasados: más del umbral de retraso (10 min). */
    public record Resumen(int total, int enRuta, int activos, int enPausa, int sinSenal, int retrasados,
                          int conAlerta) {
    }

    /** estadoRastreo es null si el viaje aún no tiene sesión de rastreo. */
    public record Viaje(UUID itineraryId, UUID busId, String origen, String destino, Instant fechaSalida,
                        Instant fechaLlegada, boolean iniciado, boolean finalizado, String estadoRastreo,
                        Posicion posicion, Long retrasoMinutos, Parada siguienteParada,
                        Parada ultimaParadaAlcanzada, Alertas alertas, Conductor conductor) {
    }

    public record Posicion(double lat, double lng, Double velocidad, Double rumbo, Instant timestamp) {
    }

    /** estimada solo en la siguiente parada (la ETA); null en la última alcanzada. */
    public record Parada(int orden, String nombre, Instant programada, Instant estimada) {
    }

    /** mayorGravedad: null si no hay alertas abiertas. */
    public record Alertas(int abiertas, String mayorGravedad) {
    }

    /** nombre: null si identity no responde. */
    public record Conductor(UUID conductorId, String nombre) {
    }
}
