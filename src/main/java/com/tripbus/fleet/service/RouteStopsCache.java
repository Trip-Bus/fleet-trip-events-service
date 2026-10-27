package com.tripbus.fleet.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.fleet.client.CatalogClient;
import com.tripbus.fleet.domain.RouteStop;
import com.tripbus.fleet.repository.RouteStopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Paradas por ruta. Se copian de Catalog la primera vez (tabla route_stops) y se guardan en Redis, así
 * el procesamiento de cada posición (US-177/US-180) no consulta la base para obtenerlas.
 */
@Component
public class RouteStopsCache {

    private static final Logger log = LoggerFactory.getLogger(RouteStopsCache.class);
    static final Duration TTL = Duration.ofHours(6);

    private final RouteStopRepository repository;
    private final CatalogClient catalogClient;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RouteStopsCache(RouteStopRepository repository, CatalogClient catalogClient, StringRedisTemplate redis,
                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.catalogClient = catalogClient;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * HT-16 (RouteCreated / RouteUpdated): reemplaza las paradas guardadas de la ruta por las que
     * manda catalog y limpia la caché de Redis (también tras el commit, por si otra lectura la llenó
     * con las viejas mientras tanto).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reemplazar(UUID routeId, List<CatalogClient.Stop> paradas) {
        repository.deleteByRouteId(routeId);
        repository.saveAll(paradas.stream()
                .map(s -> new RouteStop(routeId, s.orden(), s.nombre(), s.lat(), s.lng(), s.minutosDesdeSalida()))
                .toList());
        limpiar(routeId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                limpiar(routeId);
            }
        });
    }

    public List<RouteStop> stops(UUID routeId) {
        Optional<List<RouteStop>> cached = fromCache(routeId);
        if (cached.isPresent()) {
            return cached.get();
        }
        List<RouteStop> stops = repository.findByRouteIdOrderByOrdenAsc(routeId);
        if (stops.isEmpty()) {
            stops = fromCatalog(routeId);
        }
        if (!stops.isEmpty()) {
            toCache(routeId, stops);
        }
        return stops;
    }

    private List<RouteStop> fromCatalog(UUID routeId) {
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

    private static String key(UUID routeId) {
        return "fleet:route:" + routeId + ":stops";
    }

    private Optional<List<RouteStop>> fromCache(UUID routeId) {
        try {
            String json = redis.opsForValue().get(key(routeId));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, new TypeReference<List<CatalogClient.Stop>>() {
                    }).stream()
                    .map(s -> new RouteStop(routeId, s.orden(), s.nombre(), s.lat(), s.lng(), s.minutosDesdeSalida()))
                    .toList());
        } catch (Exception e) {
            log.debug("Caché de paradas no disponible para la ruta {}: {}", routeId, e.getMessage());
            return Optional.empty();
        }
    }

    private void toCache(UUID routeId, List<RouteStop> stops) {
        try {
            List<CatalogClient.Stop> plain = stops.stream()
                    .map(s -> new CatalogClient.Stop(s.getOrden(), s.getNombre(), s.getLat(), s.getLng(),
                            s.getMinutosDesdeSalida()))
                    .toList();
            redis.opsForValue().set(key(routeId), objectMapper.writeValueAsString(plain), TTL);
        } catch (Exception e) {
            log.debug("No se pudo cachear las paradas de la ruta {}: {}", routeId, e.getMessage());
        }
    }

    private void limpiar(UUID routeId) {
        try {
            redis.delete(key(routeId));
        } catch (RuntimeException e) {
            log.warn("No se pudo limpiar la caché de paradas de la ruta {}: {}", routeId, e.getMessage());
        }
    }
}
