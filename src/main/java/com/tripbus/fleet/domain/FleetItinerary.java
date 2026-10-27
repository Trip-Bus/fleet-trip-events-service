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

    /** US-171: cuándo y quién inició el viaje, y cuándo terminó (null si no ha pasado). */
    @Column(name = "iniciado_en")
    private Instant iniciadoEn;

    @Column(name = "iniciado_por")
    private UUID iniciadoPor;

    @Column(name = "finalizado_en")
    private Instant finalizadoEn;

    /** US-177: orden de la última parada a la que llegó el bus según su posición (0 = ninguna). */
    @Column(name = "ultima_parada_real", nullable = false)
    private int ultimaParadaReal;

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

    /** HT-16 (ItineraryRescheduled): la ventana de rastreo se mueve sola con las nuevas fechas. */
    public void reprogramar(Instant fechaSalida, Instant fechaLlegada) {
        this.fechaSalida = fechaSalida;
        this.fechaLlegada = fechaLlegada;
    }

    /** HT-16 (ItineraryBusChanged). */
    public void cambiarBus(UUID busId) {
        this.busId = busId;
    }

    /** US-171. */
    public void iniciar(UUID conductorId, Instant now) {
        this.iniciadoEn = now;
        this.iniciadoPor = conductorId;
    }

    /** US-171. */
    public void finalizar(Instant now) {
        this.finalizadoEn = now;
    }

    /** US-177: una sola vez por parada (el avance nunca retrocede). */
    public void alcanzarParada(int orden) {
        this.ultimaParadaReal = Math.max(this.ultimaParadaReal, orden);
    }

    public int getUltimaParadaReal() {
        return ultimaParadaReal;
    }

    public boolean isIniciado() {
        return iniciadoEn != null;
    }

    public boolean isFinalizado() {
        return finalizadoEn != null;
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

    public Instant getIniciadoEn() {
        return iniciadoEn;
    }

    public UUID getIniciadoPor() {
        return iniciadoPor;
    }

    public Instant getFinalizadoEn() {
        return finalizadoEn;
    }
}
