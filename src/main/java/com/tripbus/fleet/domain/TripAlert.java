package com.tripbus.fleet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** US-176: alerta de una novedad grave o un SOS, para el operador de la empresa del viaje. */
@Entity
@Table(name = "trip_alerts")
public class TripAlert {

    public enum Estado {
        ABIERTA,
        RESUELTA
    }

    @Id
    private UUID id;

    @Column(name = "itinerary_id", nullable = false)
    private UUID itineraryId;

    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "bus_id", nullable = false)
    private UUID busId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripEventType tipo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripEventType.Gravedad gravedad;

    private Double lat;

    private Double lng;

    private String mensaje;

    @Column(name = "puede_continuar")
    private Boolean puedeContinuar;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Estado estado;

    @Column(name = "creado_por")
    private UUID creadoPor;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "resuelto_por")
    private UUID resueltoPor;

    @Column(name = "resuelto_en")
    private Instant resueltoEn;

    @Column(name = "nota_resolucion")
    private String notaResolucion;

    protected TripAlert() {
    }

    public TripAlert(FleetItinerary itinerary, TripEventType tipo, Double lat, Double lng, String mensaje,
                     Boolean puedeContinuar, UUID creadoPor, Instant creadoEn) {
        this.id = UUID.randomUUID();
        this.itineraryId = itinerary.getItineraryId();
        this.companyId = itinerary.getCompanyId();
        this.busId = itinerary.getBusId();
        this.tipo = tipo;
        this.gravedad = tipo.gravedad();
        this.lat = lat;
        this.lng = lng;
        this.mensaje = mensaje;
        this.puedeContinuar = puedeContinuar;
        this.estado = Estado.ABIERTA;
        this.creadoPor = creadoPor;
        this.creadoEn = creadoEn;
    }

    /** US-177: el desvío automático es ALTA aunque un DESVIO reportado a mano sea NORMAL. */
    public TripAlert conGravedad(TripEventType.Gravedad gravedad) {
        this.gravedad = gravedad;
        return this;
    }

    public void resolver(UUID resueltoPor, String nota, Instant now) {
        this.estado = Estado.RESUELTA;
        this.resueltoPor = resueltoPor;
        this.notaResolucion = nota;
        this.resueltoEn = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getItineraryId() {
        return itineraryId;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getBusId() {
        return busId;
    }

    public TripEventType getTipo() {
        return tipo;
    }

    public TripEventType.Gravedad getGravedad() {
        return gravedad;
    }

    public Double getLat() {
        return lat;
    }

    public Double getLng() {
        return lng;
    }

    public String getMensaje() {
        return mensaje;
    }

    public Boolean getPuedeContinuar() {
        return puedeContinuar;
    }

    public Estado getEstado() {
        return estado;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public Instant getResueltoEn() {
        return resueltoEn;
    }

    public String getNotaResolucion() {
        return notaResolucion;
    }
}
