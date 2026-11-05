package com.tripbus.fleet.service;

import com.tripbus.fleet.common.redis.LeaderLock;
import com.tripbus.fleet.config.PositionRetentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Optional;

/**
 * HT-08: limpieza diaria de bus_positions, en un solo nodo (LeaderLock) y por lotes:
 * <ol>
 *   <li>las posiciones de más de keep-days (90) se borran;</li>
 *   <li>las de más de detail-days (7) se resumen a una por minuto por viaje (la primera de cada minuto; las
 *   que no tienen viaje, por bus), de a una hora por vez. Hasta dónde ya se resumió queda en Redis
 *   ({@link #MARCA}) para no recorrer cada día lo que ya está resumido.</li>
 * </ol>
 * Cada sentencia toca como máximo batch-size filas y corre en su propia transacción (autocommit).
 */
@Component
public class PositionRetentionJob {

    static final String MARCA = "fleet:positions:resumidas-hasta";
    static final Duration VENTANA_LOCK = Duration.ofMinutes(50);
    private static final Logger log = LoggerFactory.getLogger(PositionRetentionJob.class);

    private static final String BORRAR_ANTIGUAS = """
            delete from bus_positions where id in (
                select id from bus_positions where registrado_en < ? limit ?)
            """;

    private static final String RESUMIR = """
            delete from bus_positions where id in (
                select id from (
                    select id, row_number() over (
                        partition by coalesce(itinerary_id, bus_id), date_trunc('minute', registrado_en)
                        order by registrado_en, id) as n
                    from bus_positions where registrado_en >= ? and registrado_en < ?) t
                where n > 1 limit ?)
            """;

    /** Cuántas filas borró cada paso. */
    public record Resultado(int borradas, int resumidas) {
    }

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final LeaderLock leaderLock;
    private final PositionRetentionProperties properties;
    private final Clock clock;

    public PositionRetentionJob(JdbcTemplate jdbc, StringRedisTemplate redis, LeaderLock leaderLock,
                                PositionRetentionProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.leaderLock = leaderLock;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${tripbus.jobs.positions-retention-cron:0 0 4 * * *}", zone = "America/Bogota")
    public void tick() {
        leaderLock.runIfLeader("fleet-positions-retention", VENTANA_LOCK, this::ejecutar);
    }

    public Resultado ejecutar() {
        Instant now = clock.instant();
        Instant borrarAntes = now.minus(Duration.ofDays(properties.keepDays())).truncatedTo(ChronoUnit.HOURS);
        Instant resumirAntes = now.minus(Duration.ofDays(properties.detailDays())).truncatedTo(ChronoUnit.HOURS);
        int borradas = porLotes(BORRAR_ANTIGUAS, Timestamp.from(borrarAntes));
        int resumidas = 0;
        Instant desde = marca().filter(m -> m.isAfter(borrarAntes)).orElse(borrarAntes);
        for (Instant inicio = desde; inicio.isBefore(resumirAntes); inicio = inicio.plus(1, ChronoUnit.HOURS)) {
            Instant fin = inicio.plus(1, ChronoUnit.HOURS);
            resumidas += porLotes(RESUMIR, Timestamp.from(inicio), Timestamp.from(fin));
            redis.opsForValue().set(MARCA, String.valueOf(fin.toEpochMilli()));
        }
        log.info("Posiciones antiguas: {} borradas (más de {} días), {} resumidas a una por minuto (más de {} días)",
                borradas, properties.keepDays(), resumidas, properties.detailDays());
        return new Resultado(borradas, resumidas);
    }

    /** Repite la sentencia (con el límite como último parámetro) hasta que borre menos de un lote. */
    private int porLotes(String sql, Object... parametros) {
        Object[] conLimite = Arrays.copyOf(parametros, parametros.length + 1);
        conLimite[parametros.length] = properties.batchSize();
        int total = 0;
        int borradas;
        do {
            borradas = jdbc.update(sql, conLimite);
            total += borradas;
        } while (borradas >= properties.batchSize());
        return total;
    }

    private Optional<Instant> marca() {
        String valor = redis.opsForValue().get(MARCA);
        return valor == null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(Long.parseLong(valor)));
    }
}
