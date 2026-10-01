package com.tripbus.fleet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fleet_itineraries")
public class FleetItinerary {

    @Id
    @Column(name = "itinerary_id")
    private UUID itineraryId;

    @Column(name = "bus_id", nullable = false)
    private UUID busId;

    @Column(name = "route_id", nullable = false)
    private UUID routeId;

    @Column(name = "company_id")
    private UUID companyId;

    private String origen;

    private String destino;

    @Column(name = "fecha_salida", nullable = false)
    private Instant fechaSalida;

    @Column(name = "fecha_llegada", nullable = false)
    private Instant fechaLlegada;

    @Column(nullable = false)
    private boolean cancelado;

    @Column(name = "sim_ultima_parada", nullable = false)
    private int simUltimaParada = -1;

    protected FleetItinerary() {
    }

    public FleetItinerary(UUID itineraryId, UUID busId, UUID routeId, UUID companyId, String origen, String destino,
                          Instant fechaSalida, Instant fechaLlegada) {
        this.itineraryId = itineraryId;
        this.busId = busId;
        this.routeId = routeId;
        this.companyId = companyId;
        this.origen = origen;
        this.destino = destino;
        this.fechaSalida = fechaSalida;
        this.fechaLlegada = fechaLlegada;
    }

    public void cancelar() {
        this.cancelado = true;
    }

    public void avanzarSimulacion(int parada) {
        this.simUltimaParada = parada;
    }

    public UUID getItineraryId() {
        return itineraryId;
    }

    public UUID getBusId() {
        return busId;
    }

    public UUID getRouteId() {
        return routeId;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getOrigen() {
        return origen;
    }

    public String getDestino() {
        return destino;
    }

    public Instant getFechaSalida() {
        return fechaSalida;
    }

    public Instant getFechaLlegada() {
        return fechaLlegada;
    }

    public boolean isCancelado() {
        return cancelado;
    }

    public int getSimUltimaParada() {
        return simUltimaParada;
    }
}
