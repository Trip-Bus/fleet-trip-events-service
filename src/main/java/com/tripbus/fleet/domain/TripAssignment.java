package com.tripbus.fleet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** US-121: conductor asignado a un viaje con un rol (PRINCIPAL o RELEVO). */
@Entity
@Table(name = "trip_assignments")
public class TripAssignment {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "itinerary_id", nullable = false)
    private FleetItinerary itinerary;

    @Column(name = "conductor_id", nullable = false)
    private UUID conductorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DriverRole rol;

    @Column(name = "asignado_por", nullable = false)
    private UUID asignadoPor;

    @Column(name = "asignado_en", nullable = false)
    private Instant asignadoEn;

    protected TripAssignment() {
    }

    public TripAssignment(FleetItinerary itinerary, UUID conductorId, DriverRole rol, UUID asignadoPor,
                          Instant asignadoEn) {
        this.id = UUID.randomUUID();
        this.itinerary = itinerary;
        this.conductorId = conductorId;
        this.rol = rol;
        this.asignadoPor = asignadoPor;
        this.asignadoEn = asignadoEn;
    }

    /** Reemplaza al conductor del mismo rol (se actualiza la fila: el único (viaje, rol) no cambia). */
    public void reasignar(UUID conductorId, UUID asignadoPor, Instant asignadoEn) {
        this.conductorId = conductorId;
        this.asignadoPor = asignadoPor;
        this.asignadoEn = asignadoEn;
    }

    public UUID getId() {
        return id;
    }

    public FleetItinerary getItinerary() {
        return itinerary;
    }

    public UUID getConductorId() {
        return conductorId;
    }

    public DriverRole getRol() {
        return rol;
    }

    public UUID getAsignadoPor() {
        return asignadoPor;
    }

    public Instant getAsignadoEn() {
        return asignadoEn;
    }
}
