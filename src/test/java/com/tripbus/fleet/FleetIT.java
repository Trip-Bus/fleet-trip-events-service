package com.tripbus.fleet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.fleet.client.CatalogClient;
import com.tripbus.fleet.common.events.EventEnvelope;
import com.tripbus.fleet.events.FleetEventsHandler;
import com.tripbus.fleet.service.OutboxRelay;
import com.tripbus.fleet.service.TelemetryService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integración contra PostgreSQL 16 y Redis 7 reales (Testcontainers, requiere Docker): Flyway aplica
 * las migraciones REALES de db/migration, la telemetría pasa por el stream de Redis, el outbox se
 * publica en el bus de eventos y las novedades del viaje respetan la empresa del conductor.
 * Catalog se reemplaza por un mock (paradas de la ruta).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestPropertySource(properties = "TELEMETRY_STREAM=tripbus:telemetry:test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class FleetIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private FleetEventsHandler handler;
    @Autowired
    private TelemetryService telemetryService;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private Flyway flyway;

    @MockitoBean
    private CatalogClient catalogClient;

    private final UUID itineraryId = UUID.randomUUID();
    private final UUID busId = UUID.randomUUID();
    private final UUID routeId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();

    @BeforeAll
    void preparar() {
        flyway.clean();
        flyway.migrate();
        redis.delete(List.of("tripbus:telemetry:test", "tripbus:events:test"));
        var payload = objectMapper.createObjectNode()
                .put("itineraryId", itineraryId.toString()).put("busId", busId.toString())
                .put("routeId", routeId.toString()).put("companyId", companyId.toString())
                .put("origen", "Bogotá").put("destino", "Medellín")
                .put("fechaSalida", Instant.now().minus(1, ChronoUnit.HOURS).toString())
                .put("fechaLlegada", Instant.now().plus(8, ChronoUnit.HOURS).toString());
        tx.executeWithoutResult(s -> handler.handle(
                new EventEnvelope(UUID.randomUUID().toString(), "ItineraryPublished", "test", Instant.now(), payload)));
    }

    @org.junit.jupiter.api.BeforeEach
    void simularCatalog() {
        when(catalogClient.stops(routeId)).thenReturn(List.of(
                new CatalogClient.Stop(1, "Bogotá", 4.65, -74.11, 0),
                new CatalogClient.Stop(2, "Honda", 5.20, -74.73, 210),
                new CatalogClient.Stop(3, "Medellín", 6.27, -75.57, 540)));
    }

    private RequestPostProcessor conductor(UUID company) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "CONDUCTOR")
                        .claim("companyId", company.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_CONDUCTOR"));
    }

    @Test
    void laPosicionSeIngiereDeFormaAsincronaYSeConsultaComoUltimaConocida() throws Exception {
        mvc.perform(post("/telemetry/position").with(conductor(companyId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"busId":"%s","itineraryId":"%s","lat":5.0,"lng":-74.5,"velocidad":80,"rumbo":300}
                                """.formatted(busId, itineraryId)))
                .andExpect(status().isAccepted());

        // El consumidor independiente procesa lo encolado.
        List<MapRecord<String, Object, Object>> encoladas =
                redis.opsForStream().read(StreamOffset.fromStart("tripbus:telemetry:test"));
        assertThat(encoladas).isNotEmpty();
        for (MapRecord<String, Object, Object> r : encoladas) {
            Map<String, String> fields = new java.util.HashMap<>();
            r.getValue().forEach((k, v) -> fields.put(k.toString(), v.toString()));
            telemetryService.process(fields);
            telemetryService.process(fields); // idempotente por eventId
        }
        assertThat(jdbc.queryForObject("select count(*) from bus_positions", Long.class)).isEqualTo(1L);

        mvc.perform(get("/itineraries/{id}/bus-position", itineraryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_RUTA"))
                .andExpect(jsonPath("$.posicion.lat").value(5.0))
                .andExpect(jsonPath("$.paradas.length()").value(3));

        // Transactional outbox → bus de eventos con eventId = id del outbox.
        relay.relay();
        assertThat(jdbc.queryForObject("select count(*) from outbox where publicado_en is null", Long.class)).isZero();
        assertThat(redis.opsForStream().read(StreamOffset.fromStart("tripbus:events:test")))
                .anyMatch(r -> "PositionUpdated".equals(r.getValue().get("type")));
    }

    @Test
    void descartaPosicionesInvalidasOAntiguas() throws Exception {
        mvc.perform(post("/telemetry/position").with(conductor(companyId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"busId\":\"%s\",\"lat\":95,\"lng\":-74}".formatted(busId)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/telemetry/position").with(conductor(companyId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"busId\":\"%s\",\"lat\":5,\"lng\":-74,\"timestamp\":\"%s\"}"
                                .formatted(busId, Instant.now().minus(2, ChronoUnit.HOURS))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("STALE_POSITION"));
        // Un pasajero no puede enviar posiciones.
        mvc.perform(post("/telemetry/position").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PASAJERO")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"busId\":\"%s\",\"lat\":5,\"lng\":-74}".formatted(busId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void elConductorReportaNovedadesSoloDeViajesDeSuEmpresa() throws Exception {
        mvc.perform(post("/trips/{id}/events", itineraryId).with(conductor(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"RETRASO\",\"descripcion\":\"Derrumbe en la vía\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/trips/{id}/events", itineraryId).with(conductor(companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"RETRASO\",\"descripcion\":\"Derrumbe en la vía\",\"detalle\":{\"minutos\":40}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("RETRASO"));

        mvc.perform(get("/trips/{id}/timeline", itineraryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].detalle.minutos").value(40));
        assertThat(jdbc.queryForObject("select count(*) from outbox where event_type = 'TripEventReported'", Long.class))
                .isGreaterThanOrEqualTo(1L);
    }
}
