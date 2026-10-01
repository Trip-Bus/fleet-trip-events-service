package com.tripbus.fleet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bus_positions")
public class BusPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @Column(name = "bus_id", nullable = false)
    private UUID busId;

    @Column(name = "itinerary_id")
    private UUID itineraryId;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    private Double velocidad;

    private Double rumbo;

    @Column(name = "registrado_en", nullable = false)
    private Instant registradoEn;

    @Column(name = "recibido_en", nullable = false)
    private Instant recibidoEn;

    protected BusPosition() {
    }

    public BusPosition(String eventId, UUID busId, UUID itineraryId, double lat, double lng, Double velocidad,
                       Double rumbo, Instant registradoEn, Instant recibidoEn) {
        this.eventId = eventId;
        this.busId = busId;
        this.itineraryId = itineraryId;
        this.lat = lat;
        this.lng = lng;
        this.velocidad = velocidad;
        this.rumbo = rumbo;
        this.registradoEn = registradoEn;
        this.recibidoEn = recibidoEn;
    }

    public UUID getBusId() {
        return busId;
    }

    public UUID getItineraryId() {
        return itineraryId;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    public Double getVelocidad() {
        return velocidad;
    }

    public Double getRumbo() {
        return rumbo;
    }

    public Instant getRegistradoEn() {
        return registradoEn;
    }
}
