package com.tripbus.fleet.common.observability;

import com.tripbus.fleet.domain.TrackingStatus;
import com.tripbus.fleet.repository.TrackingSessionRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * HT-10: Gauge {@code tripbus_sesiones_rastreo{estado}} con cuántas sesiones de rastreo hay en cada estado.
 * Prometheus lo lee en cada scrape: el conteo (una sola consulta agrupada) se reutiliza durante
 * {@link #VIGENCIA} para no consultar la base por cada estado ni por cada scrape. Si la base falla se
 * conservan los últimos valores.
 */
@Component
public class TrackingSessionGauges {

    static final String NOMBRE = "tripbus.sesiones.rastreo";
    static final Duration VIGENCIA = Duration.ofSeconds(15);

    private static final Logger log = LoggerFactory.getLogger(TrackingSessionGauges.class);

    private final TrackingSessionRepository repository;
    private final Clock clock;
    private final Map<TrackingStatus, Long> conteo = new EnumMap<>(TrackingStatus.class);
    private Instant leidoEn = Instant.MIN;

    public TrackingSessionGauges(TrackingSessionRepository repository, MeterRegistry registry, Clock clock) {
        this.repository = repository;
        this.clock = clock;
        for (TrackingStatus estado : TrackingStatus.values()) {
            conteo.put(estado, 0L);
            Gauge.builder(NOMBRE, this, g -> g.valor(estado))
                    .description("Sesiones de rastreo por estado")
                    .tag("estado", estado.name())
                    .register(registry);
        }
    }

    synchronized double valor(TrackingStatus estado) {
        Instant now = clock.instant();
        if (Duration.between(leidoEn, now).compareTo(VIGENCIA) >= 0) {
            try {
                Map<TrackingStatus, Long> nuevo = new EnumMap<>(TrackingStatus.class);
                for (TrackingStatus e : TrackingStatus.values()) {
                    nuevo.put(e, 0L);
                }
                for (Object[] fila : repository.contarPorEstado()) {
                    nuevo.put((TrackingStatus) fila[0], ((Number) fila[1]).longValue());
                }
                conteo.putAll(nuevo);
            } catch (RuntimeException e) {
                log.warn("No se pudieron contar las sesiones de rastreo: {}", e.getMessage());
            }
            leidoEn = now;
        }
        return conteo.get(estado);
    }
}
