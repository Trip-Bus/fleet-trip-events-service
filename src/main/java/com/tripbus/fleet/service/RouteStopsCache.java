package com.tripbus.fleet.service;

import com.tripbus.fleet.client.CatalogClient;
import com.tripbus.fleet.domain.RouteStop;
import com.tripbus.fleet.repository.RouteStopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Paradas por ruta, copiadas de Catalog la primera vez que se necesitan. */
@Component
public class RouteStopsCache {

    private static final Logger log = LoggerFactory.getLogger(RouteStopsCache.class);

    private final RouteStopRepository repository;
    private final CatalogClient catalogClient;

    public RouteStopsCache(RouteStopRepository repository, CatalogClient catalogClient) {
        this.repository = repository;
        this.catalogClient = catalogClient;
    }

    public List<RouteStop> stops(UUID routeId) {
        List<RouteStop> stops = repository.findByRouteIdOrderByOrdenAsc(routeId);
        if (!stops.isEmpty()) {
            return stops;
        }
        try {
            List<RouteStop> fetched = catalogClient.stops(routeId).stream()
                    .map(s -> new RouteStop(routeId, s.orden(), s.nombre(), s.lat(), s.lng(), s.minutosDesdeSalida()))
                    .toList();
            return repository.saveAll(fetched);
        } catch (RuntimeException e) {
            log.warn("No se pudieron obtener las paradas de la ruta {}: {}", routeId, e.getMessage());
            return List.of();
        }
    }
}
