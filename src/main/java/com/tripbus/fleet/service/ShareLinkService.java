package com.tripbus.fleet.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.fleet.common.error.ApiException;
import com.tripbus.fleet.domain.FleetItinerary;
import com.tripbus.fleet.repository.FleetItineraryRepository;
import com.tripbus.fleet.web.dto.EtaView;
import com.tripbus.fleet.web.dto.ShareLinkResponse;
import com.tripbus.fleet.web.dto.SharedTripView;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * US-181: compartir mi viaje con un familiar. El enlace es un código corto de {@link #LARGO} caracteres sin
 * ambigüedad (sin 0/O ni 1/I/L) guardado en Redis ({@code fleet:share:{codigo}}) hasta la llegada estimada +
 * {@link #VIGENCIA_TRAS_LLEGADA}. Solo guarda el itinerario y el vencimiento: nunca datos del pasajero. Cada
 * usuario puede crear hasta {@link #MAX_POR_USUARIO} enlaces por viaje.
 */
@Service
public class ShareLinkService {

    static final String ALFABETO = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int LARGO = 8;
    static final int MAX_POR_USUARIO = 5;
    static final Duration VIGENCIA_TRAS_LLEGADA = Duration.ofHours(2);
    static final int INTENTOS = 5;
    private static final Pattern CODIGO = Pattern.compile("[" + ALFABETO + "]{" + LARGO + "}");

    /** Lo que se guarda en Redis por cada código. */
    record Enlace(UUID itineraryId, Instant venceEn) {
    }

    private final FleetItineraryRepository itineraryRepository;
    private final EtaStore etaStore;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ShareLinkService(FleetItineraryRepository itineraryRepository, EtaStore etaStore, StringRedisTemplate redis,
                            ObjectMapper objectMapper, Clock clock) {
        this.itineraryRepository = itineraryRepository;
        this.etaStore = etaStore;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    static String key(String codigo) {
        return "fleet:share:" + codigo;
    }

    static String keyLimite(UUID itineraryId, UUID userId) {
        return "fleet:share-limit:" + itineraryId + ":" + userId;
    }

    /** POST /share-links. 409 TRIP_CANCELLED, TRIP_FINISHED (ya venció) o SHARE_LINK_LIMIT (más de 5). */
    @Transactional(readOnly = true)
    public ShareLinkResponse crear(UUID itineraryId, UUID userId) {
        FleetItinerary itinerary = itineraryRepository.findById(itineraryId).orElseThrow(ShareLinkService::tripNotFound);
        if (itinerary.isCancelado()) {
            throw ApiException.conflict("TRIP_CANCELLED", "El viaje está cancelado");
        }
        Instant now = clock.instant();
        Instant venceEn = llegadaEstimada(itinerary).plus(VIGENCIA_TRAS_LLEGADA);
        if (!venceEn.isAfter(now)) {
            throw ApiException.conflict("TRIP_FINISHED", "El viaje ya terminó");
        }
        Duration ttl = Duration.between(now, venceEn);
        try {
            String limite = keyLimite(itineraryId, userId);
            Long creados = redis.opsForValue().increment(limite);
            redis.expire(limite, ttl);
            if (creados != null && creados > MAX_POR_USUARIO) {
                throw new ApiException(HttpStatus.CONFLICT, "SHARE_LINK_LIMIT",
                        "Ya creó el máximo de enlaces para este viaje", Map.of("maximo", MAX_POR_USUARIO));
            }
            String valor = objectMapper.writeValueAsString(new Enlace(itineraryId, venceEn));
            for (int intento = 0; intento < INTENTOS; intento++) {
                String codigo = codigo();
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(codigo), valor, ttl))) {
                    return new ShareLinkResponse(codigo, itineraryId, venceEn);
                }
            }
        } catch (JsonProcessingException | org.springframework.dao.DataAccessException e) {
            throw unavailable();
        }
        throw unavailable();
    }

    /** GET /share/{codigo} (público). Vencido, inexistente o mal escrito: 404 SHARE_LINK_NOT_FOUND. */
    @Transactional(readOnly = true)
    public SharedTripView ver(String codigo) {
        if (codigo == null || !CODIGO.matcher(codigo).matches()) {
            throw linkNotFound();
        }
        Enlace enlace;
        try {
            String valor = redis.opsForValue().get(key(codigo));
            if (valor == null) {
                throw linkNotFound();
            }
            enlace = objectMapper.readValue(valor, Enlace.class);
        } catch (JsonProcessingException | org.springframework.dao.DataAccessException e) {
            throw unavailable();
        }
        if (!enlace.venceEn().isAfter(clock.instant())) {
            throw linkNotFound();
        }
        FleetItinerary itinerary = itineraryRepository.findById(enlace.itineraryId())
                .orElseThrow(ShareLinkService::linkNotFound);
        return new SharedTripView(itinerary.getItineraryId(), itinerary.getOrigen(), itinerary.getDestino(),
                itinerary.getFechaSalida(), enlace.venceEn());
    }

    /** Fin real si ya terminó; si no, la ETA de la última parada (US-180) o, sin ETA, la llegada programada. */
    Instant llegadaEstimada(FleetItinerary itinerary) {
        if (itinerary.isFinalizado()) {
            return itinerary.getFinalizadoEn();
        }
        return etaStore.find(itinerary.getItineraryId())
                .map(EtaView::paradas)
                .filter(paradas -> !paradas.isEmpty())
                .map(paradas -> paradas.get(paradas.size() - 1).estimada())
                .orElse(itinerary.getFechaLlegada());
    }

    String codigo() {
        StringBuilder codigo = new StringBuilder(LARGO);
        for (int i = 0; i < LARGO; i++) {
            codigo.append(ALFABETO.charAt(random.nextInt(ALFABETO.length())));
        }
        return codigo.toString();
    }

    private static ApiException tripNotFound() {
        return ApiException.notFound("TRIP_NOT_FOUND", "El viaje no existe");
    }

    private static ApiException linkNotFound() {
        return ApiException.notFound("SHARE_LINK_NOT_FOUND", "El enlace no existe o ya venció");
    }

    private static ApiException unavailable() {
        return ApiException.unavailable("SHARE_UNAVAILABLE", "No fue posible usar los enlaces compartidos ahora");
    }
}
