package com.tripbus.fleet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** US-174: sesión de rastreo de un viaje (una por itinerario). */
@Entity
@Table(name = "tracking_sessions")
public class TrackingSession {

    @Id
    @Column(name = "itinerary_id")
    private UUID itineraryId;

    /** Conductor que la encendió por última vez; null si la maneja el simulador GPS. */
    @Column(name = "conductor_id")
    private UUID conductorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TrackingStatus estado;

    /** Último cambio de estado (o último start). */
    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    @Column(name = "ultima_posicion_en")
    private Instant ultimaPosicionEn;

    @Column(name = "ultima_lat")
    private Double ultimaLat;

    @Column(name = "ultima_lng")
    private Double ultimaLng;

    protected TrackingSession() {
    }

    public TrackingSession(UUID itineraryId, UUID conductorId, TrackingStatus estado, Instant now) {
        this.itineraryId = itineraryId;
        this.conductorId = conductorId;
        this.estado = estado;
        this.actualizadoEn = now;
    }

    /** Cambia el estado. Devuelve true si de verdad cambió (para publicar TrackingStatusChanged). */
    public boolean cambiar(TrackingStatus nuevo, Instant now) {
        if (estado == nuevo) {
            return false;
        }
        this.estado = nuevo;
        this.actualizadoEn = now;
        return true;
    }

    public void encender(UUID conductorId, Instant now) {
        this.conductorId = conductorId;
        this.actualizadoEn = now;
    }

    public void registrarPosicion(Instant timestamp, double lat, double lng) {
        this.ultimaPosicionEn = timestamp;
        this.ultimaLat = lat;
        this.ultimaLng = lng;
    }

    /** Momento desde el que se cuenta el tiempo sin señal: la última posición o el último cambio. */
    public Instant ultimaActividad() {
        return ultimaPosicionEn != null && ultimaPosicionEn.isAfter(actualizadoEn) ? ultimaPosicionEn : actualizadoEn;
    }

    public UUID getItineraryId() {
        return itineraryId;
    }

    public TrackingStatus getEstado() {
        return estado;
    }

    public Instant getUltimaPosicionEn() {
        return ultimaPosicionEn;
    }

    public Double getUltimaLat() {
        return ultimaLat;
    }

    public Double getUltimaLng() {
        return ultimaLng;
    }
}
