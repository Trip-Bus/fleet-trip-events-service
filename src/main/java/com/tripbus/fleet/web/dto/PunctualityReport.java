package com.tripbus.fleet.web.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * US-193: puntualidad por ruta o por conductor (GET /reports/punctuality). companyId es null si un ADMIN
 * consultó todas las empresas. En cada grupo va routeId o conductorId (y su nombre) según el agrupamiento.
 */
public record PunctualityReport(LocalDate desde, LocalDate hasta, String agrupar, UUID companyId,
                                List<Grupo> grupos) {

    /**
     * Retrasos promedio en minutos (null si ningún viaje tiene ese dato); porcentajes de 0 a 100 con un
     * decimal: viajes que llegaron con 10 min de retraso o menos y paradas a tiempo (null si no hay paradas).
     */
    public record Grupo(UUID routeId, UUID conductorId, String nombre, long viajes, Double retrasoPromedioSalidaMin,
                        Double retrasoPromedioLlegadaMin, double porcentajeViajesATiempo,
                        Double porcentajeParadasATiempo) {
    }
}
