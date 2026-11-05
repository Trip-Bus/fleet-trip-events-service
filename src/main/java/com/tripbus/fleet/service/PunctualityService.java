package com.tripbus.fleet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.fleet.common.error.ApiException;
import com.tripbus.fleet.domain.DriverRole;
import com.tripbus.fleet.domain.FleetItinerary;
import com.tripbus.fleet.domain.RouteStop;
import com.tripbus.fleet.domain.TripAssignment;
import com.tripbus.fleet.domain.TripEvent;
import com.tripbus.fleet.domain.TripEventType;
import com.tripbus.fleet.repository.FleetItineraryRepository;
import com.tripbus.fleet.repository.TripAssignmentRepository;
import com.tripbus.fleet.repository.TripEventRepository;
import com.tripbus.fleet.web.dto.TripTrackView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * US-182: puntualidad de un viaje terminado, calculada con sus novedades:
 * <ul>
 *   <li>salida real = la novedad INICIO; llegada real = la llegada a la última parada o, si no, el FIN;</li>
 *   <li>una parada está a tiempo si se llegó con {@link #TOLERANCIA_PARADA} de retraso o menos (la primera
 *   cuenta con la salida real y la última con la llegada real si no tienen LLEGADA_PARADA);</li>
 *   <li>minutos de retraso = real − programada (negativo si fue antes), en minutos completos.</li>
 * </ul>
 * Se guarda en trip_punctuality al finalizar el viaje (US-171) o cuando su rastreo pasa a FINALIZADO.
 */
@Service
public class PunctualityService {

    static final Duration TOLERANCIA_PARADA = Duration.ofMinutes(5);

    private static final String UPSERT = """
            insert into trip_punctuality (itinerary_id, salida_programada, salida_real, llegada_programada,
                llegada_real, minutos_retraso_salida, minutos_retraso_llegada, paradas_a_tiempo, paradas_total,
                conductor_id, route_id, company_id, calculado_en)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (itinerary_id) do update set
                salida_programada = excluded.salida_programada, salida_real = excluded.salida_real,
                llegada_programada = excluded.llegada_programada, llegada_real = excluded.llegada_real,
                minutos_retraso_salida = excluded.minutos_retraso_salida,
                minutos_retraso_llegada = excluded.minutos_retraso_llegada,
                paradas_a_tiempo = excluded.paradas_a_tiempo, paradas_total = excluded.paradas_total,
                conductor_id = excluded.conductor_id, route_id = excluded.route_id,
                company_id = excluded.company_id, calculado_en = excluded.calculado_en
            """;

    /** Un punto por minuto (el primero de cada minuto), en orden. Sirve también con las posiciones resumidas. */
    private static final String RECORRIDO = """
            select distinct on (date_trunc('minute', registrado_en)) lat, lng, registrado_en
            from bus_positions
            where itinerary_id = ?
            order by date_trunc('minute', registrado_en), registrado_en, id
            """;

    private static final String GUARDADA = """
            select salida_programada, salida_real, llegada_programada, llegada_real, minutos_retraso_salida,
                   minutos_retraso_llegada, paradas_a_tiempo, paradas_total
            from trip_punctuality where itinerary_id = ?
            """;

    /** Resultado del cálculo: el resumen, cada parada y el conductor principal. */
    record Calculo(TripTrackView.Puntualidad resumen, List<TripTrackView.Parada> paradas, UUID conductorId) {
    }

    private final FleetItineraryRepository itineraryRepository;
    private final TripEventRepository eventRepository;
    private final TripAssignmentRepository assignmentRepository;
    private final RouteStopsCache stopsCache;
    private final TrackingWindow trackingWindow;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PunctualityService(FleetItineraryRepository itineraryRepository, TripEventRepository eventRepository,
                              TripAssignmentRepository assignmentRepository, RouteStopsCache stopsCache,
                              TrackingWindow trackingWindow, JdbcTemplate jdbc, ObjectMapper objectMapper,
                              Clock clock) {
        this.itineraryRepository = itineraryRepository;
        this.eventRepository = eventRepository;
        this.assignmentRepository = assignmentRepository;
        this.stopsCache = stopsCache;
        this.trackingWindow = trackingWindow;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Calcula y guarda (o actualiza) la puntualidad del viaje. Un viaje cancelado no se registra. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(FleetItinerary itinerary) {
        if (itinerary.isCancelado()) {
            return;
        }
        Calculo calculo = calcular(itinerary);
        TripTrackView.Puntualidad p = calculo.resumen();
        jdbc.update(UPSERT, itinerary.getItineraryId(), ts(p.salidaProgramada()), ts(p.salidaReal()),
                ts(p.llegadaProgramada()), ts(p.llegadaReal()), p.minutosRetrasoSalida(), p.minutosRetrasoLlegada(),
                p.paradasATiempo(), p.paradasTotal(), calculo.conductorId(), itinerary.getRouteId(),
                itinerary.getCompanyId(), ts(clock.instant()));
    }

    /**
     * GET /itineraries/{id}/track: solo viajes terminados (finalizados o pasada su ventana de rastreo) y no
     * cancelados; si no, 404 TRIP_NOT_FINISHED. El resumen es el guardado (o se calcula si no hay).
     */
    @Transactional(readOnly = true)
    public TripTrackView track(UUID itineraryId) {
        FleetItinerary itinerary = itineraryRepository.findById(itineraryId)
                .orElseThrow(() -> ApiException.notFound("TRIP_NOT_FOUND", "El viaje no existe"));
        boolean terminado = itinerary.isFinalizado() || clock.instant().isAfter(trackingWindow.end(itinerary));
        if (itinerary.isCancelado() || !terminado) {
            throw ApiException.notFound("TRIP_NOT_FINISHED", "El recorrido solo está disponible para viajes terminados");
        }
        Calculo calculo = calcular(itinerary);
        List<TripTrackView.Punto> recorrido = jdbc.query(RECORRIDO, (rs, n) -> new TripTrackView.Punto(
                rs.getDouble("lat"), rs.getDouble("lng"), instant(rs, "registrado_en")), itineraryId);
        TripTrackView.Puntualidad resumen = jdbc.query(GUARDADA, PunctualityService::puntualidad, itineraryId)
                .stream().findFirst().orElse(calculo.resumen());
        return new TripTrackView(itineraryId, itinerary.getOrigen(), itinerary.getDestino(), recorrido,
                calculo.paradas(), resumen);
    }

    Calculo calcular(FleetItinerary itinerary) {
        Instant inicio = itinerary.getIniciadoEn();
        Instant fin = itinerary.getFinalizadoEn();
        boolean hayInicio = false;
        boolean hayFin = false;
        Map<Integer, Instant> llegadas = new HashMap<>();
        for (TripEvent event : eventRepository.findByItineraryIdOrderByOcurridoEnAsc(itinerary.getItineraryId())) {
            if (event.getTipo() == TripEventType.INICIO && !hayInicio) {
                inicio = event.getOcurridoEn();
                hayInicio = true;
            } else if (event.getTipo() == TripEventType.FIN && !hayFin) {
                fin = event.getOcurridoEn();
                hayFin = true;
            } else if (event.getTipo() == TripEventType.LLEGADA_PARADA) {
                orden(event).ifPresent(orden -> llegadas.putIfAbsent(orden, event.getOcurridoEn()));
            }
        }
        List<RouteStop> stops = stopsCache.stops(itinerary.getRouteId());
        Instant llegadaReal = stops.isEmpty() ? fin
                : Optional.ofNullable(llegadas.get(stops.get(stops.size() - 1).getOrden())).orElse(fin);

        List<TripTrackView.Parada> paradas = new ArrayList<>();
        int aTiempo = 0;
        for (int i = 0; i < stops.size(); i++) {
            RouteStop stop = stops.get(i);
            Instant programada = itinerary.getFechaSalida().plusSeconds(stop.getMinutosDesdeSalida() * 60L);
            Instant real = llegadas.get(stop.getOrden());
            if (real == null && i == 0) {
                real = inicio;
            } else if (real == null && i == stops.size() - 1) {
                real = llegadaReal;
            }
            boolean puntual = real != null && !real.isAfter(programada.plus(TOLERANCIA_PARADA));
            if (puntual) {
                aTiempo++;
            }
            paradas.add(new TripTrackView.Parada(stop.getOrden(), stop.getNombre(), stop.getLat(), stop.getLng(),
                    programada, real, puntual));
        }
        TripTrackView.Puntualidad resumen = new TripTrackView.Puntualidad(itinerary.getFechaSalida(), inicio,
                itinerary.getFechaLlegada(), llegadaReal, minutos(itinerary.getFechaSalida(), inicio),
                minutos(itinerary.getFechaLlegada(), llegadaReal), aTiempo, stops.size());
        UUID conductorId = assignmentRepository.findByViajeYRol(itinerary.getItineraryId(), DriverRole.PRINCIPAL)
                .map(TripAssignment::getConductorId).orElse(itinerary.getIniciadoPor());
        return new Calculo(resumen, paradas, conductorId);
    }

    /** orden de la parada en el detalle de LLEGADA_PARADA ({"orden": n}). */
    private Optional<Integer> orden(TripEvent event) {
        try {
            JsonNode detalle = event.getPayload() == null ? null : objectMapper.readTree(event.getPayload());
            return detalle != null && detalle.path("orden").isInt()
                    ? Optional.of(detalle.path("orden").asInt()) : Optional.empty();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Optional.empty();
        }
    }

    static Integer minutos(Instant programada, Instant real) {
        return real == null ? null : Math.toIntExact(Duration.between(programada, real).toMinutes());
    }

    private static TripTrackView.Puntualidad puntualidad(ResultSet rs, int fila) throws SQLException {
        return new TripTrackView.Puntualidad(instant(rs, "salida_programada"), instant(rs, "salida_real"),
                instant(rs, "llegada_programada"), instant(rs, "llegada_real"),
                (Integer) rs.getObject("minutos_retraso_salida"), (Integer) rs.getObject("minutos_retraso_llegada"),
                rs.getInt("paradas_a_tiempo"), rs.getInt("paradas_total"));
    }

    private static Instant instant(ResultSet rs, String columna) throws SQLException {
        Timestamp t = rs.getTimestamp(columna);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
