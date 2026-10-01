package com.tripbus.fleet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "route_stops")
@IdClass(RouteStop.Key.class)
public class RouteStop {

    @Id
    @Column(name = "route_id")
    private UUID routeId;

    @Id
    private int orden;

    @Column(nullable = false)
    private String nombre;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    @Column(name = "minutos_desde_salida", nullable = false)
    private int minutosDesdeSalida;

    protected RouteStop() {
    }

    public RouteStop(UUID routeId, int orden, String nombre, double lat, double lng, int minutosDesdeSalida) {
        this.routeId = routeId;
        this.orden = orden;
        this.nombre = nombre;
        this.lat = lat;
        this.lng = lng;
        this.minutosDesdeSalida = minutosDesdeSalida;
    }

    public UUID getRouteId() {
        return routeId;
    }

    public int getOrden() {
        return orden;
    }

    public String getNombre() {
        return nombre;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    public int getMinutosDesdeSalida() {
        return minutosDesdeSalida;
    }

    public static class Key implements Serializable {
        private UUID routeId;
        private int orden;

        public Key() {
        }

        public Key(UUID routeId, int orden) {
            this.routeId = routeId;
            this.orden = orden;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.orden == orden && Objects.equals(k.routeId, routeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(routeId, orden);
        }
    }
}
