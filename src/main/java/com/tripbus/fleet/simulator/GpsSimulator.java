package com.tripbus.fleet.simulator;

import com.tripbus.fleet.common.geo.Geo;
import com.tripbus.fleet.common.redis.LeaderLock;
import com.tripbus.fleet.domain.FleetItinerary;
import com.tripbus.fleet.domain.RouteStop;
import com.tripbus.fleet.domain.TripEventType;
import com.tripbus.fleet.repository.FleetItineraryRepository;
import com.tripbus.fleet.service.DriverTrackingService;
import com.tripbus.fleet.service.RouteStopsCache;
import com.tripbus.fleet.service.TelemetryService;
import com.tripbus.fleet.service.TripService;
import com.tripbus.fleet.web.dto.PositionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulador GPS del MVP ("corre como job separado"): mueve cada bus en curso a lo largo de
 * las paradas de su ruta según el horario, publicando la posición por la MISMA API de
 * ingesta que usaría un GPS real. También genera las novedades automáticas del viaje
 * (INICIO, LLEGADA_PARADA, FIN). Se activa con SIMULATOR_ENABLED=true y corre en un solo nodo.
 */
@Component
@ConditionalOnProperty(name = "tripbus.fleet.simulator-enabled", havingValue = "true")
public class GpsSimulator {

    private static final Logger log = LoggerFactory.getLogger(GpsSimulator.class);
    static final String ORIGEN = "SIMULADOR";
    private static final String PARADA = "parada";

    private final FleetItineraryRepository itineraryRepository;
    private final RouteStopsCache stopsCache;
    private final TelemetryService telemetryService;
    private final TripService tripService;
    private final DriverTrackingService trackingService;
    private final LeaderLock leaderLock;
    private final TransactionTemplate tx;
    private final Clock clock;

    public GpsSimulator(FleetItineraryRepository itineraryRepository, RouteStopsCache stopsCache,
                        TelemetryService telemetryService, TripService tripService,
                        DriverTrackingService trackingService, LeaderLock leaderLock,
                        TransactionTemplate tx, Clock clock) {
        this.itineraryRepository = itineraryRepository;
        this.stopsCache = stopsCache;
        this.telemetryService = telemetryService;
        this.tripService = tripService;
        this.trackingService = trackingService;
        this.leaderLock = leaderLock;
        this.tx = tx;
        this.clock = clock;
        log.info("Simulador GPS habilitado");
    }

    @Scheduled(fixedDelayString = "${tripbus.jobs.simulator-interval-ms:5000}", initialDelay = 10000)
    public void tick() {
        leaderLock.runIfLeader("fleet-gps-simulator", Duration.ofSeconds(4), this::simulate);
    }

    public void simulate() {
        Instant now = clock.instant();
        List<FleetItinerary> enCurso = itineraryRepository.findEnCurso(now, now.minus(Duration.ofMinutes(2)),
                PageRequest.of(0, 200));
        for (FleetItinerary itinerary : enCurso) {
            try {
                step(itinerary, now);
            } catch (RuntimeException e) {
                log.warn("Simulador: error en el viaje {}: {}", itinerary.getItineraryId(), e.getMessage());
            }
        }
    }

    private void step(FleetItinerary itinerary, Instant now) {
        List<RouteStop> stops = stopsCache.stops(itinerary.getRouteId());
        if (stops.size() < 2) {
            return;
        }
        double elapsedMin = Duration.between(itinerary.getFechaSalida(), now).toMillis() / 60_000.0;

        if (now.isBefore(itinerary.getFechaLlegada())) {
            Point p = interpolate(stops, elapsedMin);
            telemetryService.ingest(new PositionRequest(itinerary.getBusId(), itinerary.getItineraryId(), p.lat(),
                    p.lng(), p.speed(), p.bearing(), now));
            // US-174: en QA el mapa muestra el estado de rastreo igual que con un conductor real.
            trackingService.markSimulated(itinerary.getItineraryId(), now, p.lat(), p.lng());
        }

        tx.executeWithoutResult(s -> {
            // Bloqueado: la detección automática (US-177) de una posición del mismo viaje espera, y así
            // ninguno de los dos duplica la LLEGADA_PARADA del otro.
            FleetItinerary it = itineraryRepository.findByIdForUpdate(itinerary.getItineraryId()).orElseThrow();
            int last = it.getSimUltimaParada();
            if (last < 0) {
                tripService.record(it, TripEventType.INICIO, "El bus salió de " + stops.get(0).getNombre(),
                        Map.of(PARADA, stops.get(0).getNombre()), null, ORIGEN);
                last = 0;
            }
            last = paradasIntermedias(it, stops, last, elapsedMin);
            if (!now.isBefore(it.getFechaLlegada()) && last < stops.size() - 1) {
                RouteStop destino = stops.get(stops.size() - 1);
                tripService.record(it, TripEventType.FIN, "El bus llegó a " + destino.getNombre(),
                        Map.of(PARADA, destino.getNombre()), null, ORIGEN);
                last = stops.size() - 1;
            }
            if (last != it.getSimUltimaParada()) {
                it.avanzarSimulacion(last);
            }
        });
    }

    /**
     * LLEGADA_PARADA de las paradas intermedias cuyo horario ya pasó, sin duplicar la que ya registró la
     * detección automática (US-177). Devuelve el índice de la última parada anunciada.
     */
    private int paradasIntermedias(FleetItinerary it, List<RouteStop> stops, int last, double elapsedMin) {
        int ultima = last;
        for (int i = last + 1; i < stops.size() - 1; i++) {
            RouteStop stop = stops.get(i);
            if (stop.getMinutosDesdeSalida() <= elapsedMin) {
                if (!tripService.existeLlegada(it.getItineraryId(), stop.getOrden())) {
                    tripService.record(it, TripEventType.LLEGADA_PARADA, "Llegada a " + stop.getNombre(),
                            Map.of(PARADA, stop.getNombre(), "orden", stop.getOrden()), null, ORIGEN);
                }
                ultima = i;
            }
        }
        return ultima;
    }

    record Point(double lat, double lng, double speed, double bearing) {
    }

    static Point interpolate(List<RouteStop> stops, double elapsedMin) {
        RouteStop last = stops.get(stops.size() - 1);
        if (elapsedMin >= last.getMinutosDesdeSalida()) {
            return new Point(last.getLat(), last.getLng(), 0, 0);
        }
        for (int i = 0; i < stops.size() - 1; i++) {
            RouteStop a = stops.get(i);
            RouteStop b = stops.get(i + 1);
            if (elapsedMin < b.getMinutosDesdeSalida()) {
                double span = Math.max(1, b.getMinutosDesdeSalida() - a.getMinutosDesdeSalida());
                double f = Math.max(0, Math.min(1, (elapsedMin - a.getMinutosDesdeSalida()) / span));
                double lat = a.getLat() + (b.getLat() - a.getLat()) * f;
                double lng = a.getLng() + (b.getLng() - a.getLng()) * f;
                double km = haversineKm(a.getLat(), a.getLng(), b.getLat(), b.getLng());
                double speed = Math.min(110, km / (span / 60.0)) * (0.9 + ThreadLocalRandom.current().nextDouble() * 0.2);
                return new Point(lat, lng, Math.round(speed * 10) / 10.0, bearing(a.getLat(), a.getLng(), b.getLat(), b.getLng()));
            }
        }
        return new Point(last.getLat(), last.getLng(), 0, 0);
    }

    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        return Geo.haversineKm(lat1, lon1, lat2, lon2);
    }

    static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double y = Math.sin(Math.toRadians(lon2 - lon1)) * Math.cos(Math.toRadians(lat2));
        double x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2))
                - Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(Math.toRadians(lon2 - lon1));
        double deg = Math.toDegrees(Math.atan2(y, x));
        return Math.round(((deg + 360) % 360) * 10) / 10.0;
    }
}
