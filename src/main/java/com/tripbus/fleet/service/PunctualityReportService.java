package com.tripbus.fleet.service;

import com.tripbus.fleet.common.error.ApiException;
import com.tripbus.fleet.web.dto.PunctualityReport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * US-193: puntualidad por ruta o por conductor con consultas agregadas sobre trip_punctuality (una sola
 * consulta por reporte). Un viaje llega a tiempo con {@link #TOLERANCIA_LLEGADA_MIN} min de retraso o menos.
 * Las fechas son días de America/Bogota (por fecha de salida programada), máximo {@link #MAX_DIAS} días.
 */
@Service
public class PunctualityReportService {

    static final int MAX_DIAS = 366;
    static final int TOLERANCIA_LLEGADA_MIN = 10;
    static final String POR_RUTA = "ruta";
    static final String POR_CONDUCTOR = "conductor";
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private static final String METRICAS = """
            count(*) as viajes,
            avg(minutos_retraso_salida) as retraso_salida,
            avg(minutos_retraso_llegada) as retraso_llegada,
            count(*) filter (where minutos_retraso_llegada <= ?) as viajes_a_tiempo,
            coalesce(sum(paradas_a_tiempo), 0) as paradas_a_tiempo,
            coalesce(sum(paradas_total), 0) as paradas_total
            from trip_punctuality
            where salida_programada >= ? and salida_programada < ?
              and (cast(? as uuid) is null or company_id = cast(? as uuid))
            """;
    private static final String SQL_RUTA = "select route_id as clave, " + METRICAS
            + " group by route_id order by viajes desc, route_id";
    private static final String SQL_CONDUCTOR = "select conductor_id as clave, " + METRICAS
            + " group by conductor_id order by viajes desc, conductor_id";

    private final JdbcTemplate jdbc;
    private final DriverNameCache driverNames;

    public PunctualityReportService(JdbcTemplate jdbc, DriverNameCache driverNames) {
        this.jdbc = jdbc;
        this.driverNames = driverNames;
    }

    private record Fila(UUID clave, long viajes, Double retrasoSalida, Double retrasoLlegada, long viajesATiempo,
                        long paradasATiempo, long paradasTotal) {
    }

    /**
     * GET /reports/punctuality. Un OPERADOR ve solo su empresa (el companyId de la consulta se ignora); un
     * ADMIN la de companyId o, sin él, todas.
     */
    @Transactional(readOnly = true)
    public PunctualityReport report(LocalDate desde, LocalDate hasta, String agrupar, UUID companyIdConsulta,
                                    boolean isAdmin, UUID companyIdUsuario) {
        if (hasta.isBefore(desde)) {
            throw ApiException.badRequest("INVALID_RANGE", "hasta no puede ser anterior a desde");
        }
        if (ChronoUnit.DAYS.between(desde, hasta) + 1 > MAX_DIAS) {
            throw ApiException.badRequest("RANGE_TOO_LARGE", "El rango no puede superar " + MAX_DIAS + " días");
        }
        boolean porConductor = POR_CONDUCTOR.equals(agrupar);
        if (!porConductor && !POR_RUTA.equals(agrupar)) {
            throw ApiException.badRequest("INVALID_GROUPING", "agrupar debe ser ruta o conductor");
        }
        UUID companyId = isAdmin ? companyIdConsulta : companyIdUsuario;
        if (!isAdmin && companyId == null) {
            throw ApiException.forbidden("NO_COMPANY", "El operador no tiene empresa");
        }
        List<Fila> filas = jdbc.query(porConductor ? SQL_CONDUCTOR : SQL_RUTA, PunctualityReportService::fila,
                TOLERANCIA_LLEGADA_MIN, Timestamp.from(desde.atStartOfDay(BOGOTA).toInstant()),
                Timestamp.from(hasta.plusDays(1).atStartOfDay(BOGOTA).toInstant()), companyId, companyId);
        Map<UUID, String> nombres = porConductor
                ? driverNames.nombres(filas.stream().map(Fila::clave).filter(Objects::nonNull).toList())
                : Map.of();
        List<PunctualityReport.Grupo> grupos = filas.stream().map(f -> new PunctualityReport.Grupo(
                porConductor ? null : f.clave(), porConductor ? f.clave() : null,
                f.clave() == null ? null : nombres.get(f.clave()), f.viajes(), redondear(f.retrasoSalida()),
                redondear(f.retrasoLlegada()), porcentaje(f.viajesATiempo(), f.viajes()),
                f.paradasTotal() == 0 ? null : porcentaje(f.paradasATiempo(), f.paradasTotal()))).toList();
        return new PunctualityReport(desde, hasta, agrupar, companyId, grupos);
    }

    private static Fila fila(ResultSet rs, int n) throws SQLException {
        return new Fila(rs.getObject("clave", UUID.class), rs.getLong("viajes"), decimal(rs, "retraso_salida"),
                decimal(rs, "retraso_llegada"), rs.getLong("viajes_a_tiempo"), rs.getLong("paradas_a_tiempo"),
                rs.getLong("paradas_total"));
    }

    private static Double decimal(ResultSet rs, String columna) throws SQLException {
        BigDecimal valor = rs.getBigDecimal(columna);
        return valor == null ? null : valor.doubleValue();
    }

    static double porcentaje(long parte, long total) {
        return BigDecimal.valueOf(parte * 100.0 / total).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    static Double redondear(Double valor) {
        return valor == null ? null : BigDecimal.valueOf(valor).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
