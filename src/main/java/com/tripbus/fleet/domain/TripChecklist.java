package com.tripbus.fleet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** US-171: checklist que el conductor confirma al iniciar el viaje (una por viaje). */
@Entity
@Table(name = "trip_checklists")
public class TripChecklist {

    @Id
    @Column(name = "itinerary_id")
    private UUID itineraryId;

    @Column(name = "conductor_id", nullable = false)
    private UUID conductorId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Boolean> items;

    private String observacion;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    protected TripChecklist() {
    }

    public TripChecklist(UUID itineraryId, UUID conductorId, Map<String, Boolean> items, String observacion,
                         Instant creadoEn) {
        this.itineraryId = itineraryId;
        this.conductorId = conductorId;
        this.items = items;
        this.observacion = observacion;
        this.creadoEn = creadoEn;
    }

    public Map<String, Boolean> getItems() {
        return items;
    }
}
