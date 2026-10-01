package com.tripbus.fleet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trip_events")
public class TripEvent {

    @Id
    private UUID id;

    @Column(name = "itinerary_id", nullable = false)
    private UUID itineraryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripEventType tipo;

    private String descripcion;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private String origen;

    @Column(name = "ocurrido_en", nullable = false)
    private Instant ocurridoEn;

    protected TripEvent() {
    }

    public TripEvent(UUID itineraryId, TripEventType tipo, String descripcion, String payload, UUID userId,
                     String origen, Instant ocurridoEn) {
        this.id = UUID.randomUUID();
        this.itineraryId = itineraryId;
        this.tipo = tipo;
        this.descripcion = descripcion;
        this.payload = payload;
        this.userId = userId;
        this.origen = origen;
        this.ocurridoEn = ocurridoEn;
    }

    public UUID getId() {
        return id;
    }

    public UUID getItineraryId() {
        return itineraryId;
    }

    public TripEventType getTipo() {
        return tipo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public String getPayload() {
        return payload;
    }

    public String getOrigen() {
        return origen;
    }

    public Instant getOcurridoEn() {
        return ocurridoEn;
    }
}
