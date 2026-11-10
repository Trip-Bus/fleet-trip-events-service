package com.tripbus.fleet.service;

import com.tripbus.fleet.client.IdentityClient;
import com.tripbus.fleet.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * US-178: nombre de los conductores para el mapa de la flota, desde identity (GET /internal/users/{id})
 * con caché en memoria de 10 minutos. Si identity no responde, el nombre queda en null y el mapa carga
 * igual: tras una falla no se vuelve a llamar a identity durante {@link #PAUSA_TRAS_FALLA}, para que un
 * mapa con muchos conductores no espere el timeout de cada uno.
 */
@Component
public class DriverNameCache {

    static final Duration TTL = Duration.ofMinutes(10);
    static final Duration PAUSA_TRAS_FALLA = Duration.ofSeconds(30);
    /** Con más entradas que esto se limpian las vencidas antes de seguir. */
    static final int LIMPIAR_DESDE = 5_000;

    private static final Logger log = LoggerFactory.getLogger(DriverNameCache.class);

    private record Entrada(String nombre, Instant venceEn) {
    }

    private final IdentityClient identityClient;
    private final Clock clock;
    private final Map<UUID, Entrada> cache = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> identityCaidoHasta = new AtomicReference<>(Instant.MIN);

    public DriverNameCache(IdentityClient identityClient, Clock clock) {
        this.identityClient = identityClient;
        this.clock = clock;
    }

    /** Nombre de cada conductor (null si identity no lo conoce o no responde). */
    public Map<UUID, String> nombres(Collection<UUID> conductorIds) {
        Instant now = clock.instant();
        if (cache.size() > LIMPIAR_DESDE) {
            cache.values().removeIf(e -> !e.venceEn().isAfter(now));
        }
        Map<UUID, String> nombres = new HashMap<>();
        for (UUID id : conductorIds) {
            nombres.put(id, nombre(id, now));
        }
        return nombres;
    }

    private String nombre(UUID id, Instant now) {
        Entrada entrada = cache.get(id);
        if (entrada != null && entrada.venceEn().isAfter(now)) {
            return entrada.nombre();
        }
        if (now.isBefore(identityCaidoHasta.get())) {
            return null;
        }
        try {
            String nombre = identityClient.user(id).map(IdentityClient.Usuario::nombre).orElse(null);
            cache.put(id, new Entrada(nombre, now.plus(TTL)));
            return nombre;
        } catch (ApiException e) {
            log.warn("Identity no respondió el nombre del conductor {}: se muestra sin nombre", id);
            identityCaidoHasta.set(now.plus(PAUSA_TRAS_FALLA));
            return null;
        }
    }
}
