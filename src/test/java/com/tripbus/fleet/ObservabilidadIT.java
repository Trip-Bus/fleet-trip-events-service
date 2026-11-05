package com.tripbus.fleet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.tripbus.fleet.client.CatalogClient;
import com.tripbus.fleet.client.IdentityClient;
import com.tripbus.fleet.common.events.EventEnvelope;
import com.tripbus.fleet.common.events.EventPublisher;
import com.tripbus.fleet.events.FleetEventsHandler;
import com.tripbus.fleet.events.IdentityEventsHandler;
import com.tripbus.fleet.service.Outbox;
import com.tripbus.fleet.service.OutboxRelay;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HT-10 contra PostgreSQL y Redis reales (Testcontainers), con tracing y métricas encendidos: el contexto de
 * la traza viaja por el stream de telemetría, el outbox y las llamadas a identity; las métricas propias salen
 * en /actuator/prometheus; y UserDeleted saca al conductor de sus viajes pendientes. Aquí los consumidores de
 * los streams (eventos y telemetría) sí corren, como en un entorno real.
 */
@SpringBootTest(properties = {"management.tracing.sampling.probability=1.0",
        "tripbus.jobs.tracking-status-ms=3600000", "TELEMETRY_STREAM=tripbus:telemetry:obs",
        "tripbus.events.consumer-enabled=true"})
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
class ObservabilidadIT {

    private static final String TELEMETRIA = "tripbus:telemetry:obs";
    private static final String EVENTOS = "tripbus:events:test";

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final WireMockServer identity = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        identity.start();
        postgres.start();
        redisContainer.start();
    }

    @DynamicPropertySource
    static void identityUrl(DynamicPropertyRegistry registry) {
        registry.add("tripbus.services.identity-url", identity::baseUrl);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private FleetEventsHandler fleetHandler;
    @Autowired
    private IdentityEventsHandler identityHandler;
    @Autowired
    private IdentityClient identityClient;
    @Autowired
    private EventPublisher publisher;
    @Autowired
    private Outbox outbox;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private Tracer tracer;
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

    private final UUID companyId = UUID.randomUUID();

    @BeforeAll
    void preparar() {
        flyway.clean();
        flyway.migrate();
        // Redis es nuevo para esta clase: no se borran los streams (se perderían los consumer groups).
    }

    @AfterAll
    void detenerIdentity() {
        identity.stop();
    }

    // ---------- utilidades ----------

    private UUID publicar(Instant salida, Instant llegada) {
        UUID itinerary = UUID.randomUUID();
        var payload = objectMapper.createObjectNode()
                .put("itineraryId", itinerary.toString()).put("busId", UUID.randomUUID().toString())
                .put("routeId", UUID.randomUUID().toString()).put("companyId", companyId.toString())
                .put("origen", "Bogotá").put("destino", "Tunja")
                .put("fechaSalida", salida.toString()).put("fechaLlegada", llegada.toString());
        tx.executeWithoutResult(s -> fleetHandler.handle(
                new EventEnvelope(UUID.randomUUID().toString(), "ItineraryPublished", "test", Instant.now(), payload)));
        return itinerary;
    }

    private void asignar(UUID itinerary, UUID conductor, String rol) {
        jdbc.update("insert into trip_assignments (id, itinerary_id, conductor_id, rol, asignado_por, asignado_en) "
                + "values (?, ?, ?, ?, ?, now())", UUID.randomUUID(), itinerary, conductor, rol, UUID.randomUUID());
    }

    private List<UUID> asignadosA(UUID itinerary) {
        return jdbc.queryForList("select conductor_id from trip_assignments where itinerary_id = ?", UUID.class,
                itinerary);
    }

    private List<String> outbox(String tipo, UUID id) {
        return jdbc.queryForList("select payload from outbox where event_type = ? and payload like ?",
                String.class, tipo, "%" + id + "%");
    }

    private void usuarioEliminado(UUID userId) {
        var payload = objectMapper.createObjectNode().put("userId", userId.toString());
        tx.executeWithoutResult(s -> identityHandler.handle(
                new EventEnvelope(UUID.randomUUID().toString(), "UserDeleted", "identity", Instant.now(), payload)));
    }

    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private RequestPostProcessor operador() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "OPERADOR")
                        .claim("companyId", companyId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_OPERADOR"));
    }

    private List<MapRecord<String, Object, Object>> stream(String key) {
        return redis.opsForStream().read(StreamOffset.fromStart(key));
    }

    // ---------- UserDeleted ----------

    @Test
    void conductorEliminadoSaleDeSusViajesPendientesConConflicto() throws Exception {
        UUID conductor = UUID.randomUUID();
        UUID relevo = UUID.randomUUID();
        Instant ahora = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        UUID futuro = publicar(ahora.plus(2, ChronoUnit.DAYS), ahora.plus(2, ChronoUnit.DAYS).plus(5, ChronoUnit.HOURS));
        UUID enCurso = publicar(ahora.minus(1, ChronoUnit.HOURS), ahora.plus(3, ChronoUnit.HOURS));
        UUID terminado = publicar(ahora.minus(3, ChronoUnit.DAYS), ahora.minus(3, ChronoUnit.DAYS).plus(5, ChronoUnit.HOURS));
        asignar(futuro, conductor, "PRINCIPAL");
        asignar(futuro, relevo, "RELEVO");
        asignar(enCurso, conductor, "PRINCIPAL");
        asignar(terminado, conductor, "PRINCIPAL");

        // Llega por el stream de eventos, como lo publica identity.
        publisher.publish("UserDeleted", Map.of("userId", conductor));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(asignadosA(enCurso)).isEmpty());
        assertThat(asignadosA(futuro)).containsExactly(relevo);
        // El historial de un viaje que ya terminó no se toca.
        assertThat(asignadosA(terminado)).containsExactly(conductor);
        List<String> conflictos = outbox("DriverAssignmentConflict", futuro);
        assertThat(conflictos).hasSize(1);
        var conflicto = objectMapper.readTree(conflictos.get(0));
        assertThat(conflicto.path("conductorId").asText()).isEqualTo(conductor.toString());
        assertThat(conflicto.path("motivo").asText()).isEqualTo("CONDUCTOR_ELIMINADO");
        assertThat(conflicto.path("rol").asText()).isEqualTo("PRINCIPAL");
        assertThat(conflicto.path("companyId").asText()).isEqualTo(companyId.toString());
        assertThat(outbox("DriverAssignmentConflict", enCurso)).hasSize(1);
        assertThat(outbox("DriverUnassigned", futuro)).hasSize(1);
        assertThat(outbox("DriverAssignmentConflict", terminado)).isEmpty();
    }

    @Test
    void usuarioEliminadoQueNoEraConductorNoCambiaNada() {
        long antes = jdbc.queryForObject("select count(*) from outbox", Long.class);

        usuarioEliminado(UUID.randomUUID());

        assertThat(jdbc.queryForObject("select count(*) from outbox", Long.class)).isEqualTo(antes);
    }

    // ---------- trazas ----------

    @Test
    void laPosicionEncoladaLlevaLaTrazaDeLaPeticion() throws Exception {
        UUID busId = UUID.randomUUID();
        mvc.perform(post("/telemetry/position").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"busId\":\"%s\",\"lat\":4.65,\"lng\":-74.1}".formatted(busId)))
                .andExpect(status().isAccepted());

        MapRecord<String, Object, Object> registro = stream(TELEMETRIA).stream()
                .filter(r -> busId.toString().equals(r.getValue().get("busId"))).findFirst().orElseThrow();
        assertThat((String) registro.getValue().get("traceparent")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        // El consumidor de telemetría la procesa.
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "select count(*) from bus_positions where bus_id = ?", Long.class, busId) == 1);
    }

    @Test
    void elEventoDelOutboxSigueLaTrazaDeQuienLoGenero() {
        UUID marca = UUID.randomUUID();
        Span span = tracer.nextSpan().name("prueba").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            tx.executeWithoutResult(s -> outbox.add("PruebaTraza", Map.of("marca", marca)));
        } finally {
            span.end();
        }
        String guardado = jdbc.queryForObject("select trace_parent from outbox where payload like ?", String.class,
                "%" + marca + "%");
        assertThat(guardado).contains(span.context().traceId());

        relay.relay();

        MapRecord<String, Object, Object> evento = stream(EVENTOS).stream()
                .filter(r -> String.valueOf(r.getValue().get("payload")).contains(marca.toString()))
                .findFirst().orElseThrow();
        assertThat(evento.getValue().get("traceparent")).isEqualTo(guardado);
    }

    @Test
    void lasLlamadasAIdentityPropaganLaTraza() {
        UUID userId = UUID.randomUUID();
        identity.stubFor(get(urlEqualTo("/internal/users/" + userId)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"%s\",\"nombre\":\"Ana\",\"rol\":\"CONDUCTOR\",\"companyId\":\"%s\",\"activo\":true}"
                        .formatted(userId, companyId))));
        // WireMock en frío puede pasar del timeout de lectura: se calienta fuera de la traza (Awaitility
        // consulta en otro hilo).
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() -> identityClient.user(userId).isPresent());
        identity.resetRequests();
        Span span = tracer.nextSpan().name("prueba").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            assertThat(identityClient.user(userId)).isPresent();
        } finally {
            span.end();
        }

        identity.verify(getRequestedFor(urlEqualTo("/internal/users/" + userId))
                .withHeader("traceparent", matching("00-" + span.context().traceId() + "-[0-9a-f]{16}-01")));
    }

    // ---------- métricas ----------

    @Test
    void lasMetricasPropiasSalenEnPrometheus() throws Exception {
        UUID viaje = publicar(Instant.now().minus(10, ChronoUnit.MINUTES), Instant.now().plus(5, ChronoUnit.HOURS));
        mvc.perform(post("/telemetry/position").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"busId\":\"%s\",\"lat\":0,\"lng\":0}".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/trips/{id}/events", viaje).with(operador()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"AVERIA\",\"descripcion\":\"Llanta pinchada\",\"lat\":4.7,\"lng\":-74.0}"))
                .andExpect(status().isCreated());

        String scrape = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/actuator/prometheus"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(scrape)
                .containsPattern("tripbus_posiciones_total\\{[^}]*motivo=\"INVALID_COORDINATES\",resultado=\"descartada\"")
                .containsPattern("tripbus_alertas_total\\{[^}]*gravedad=\"ALTA\",tipo=\"AVERIA\"")
                .containsPattern("tripbus_sesiones_rastreo\\{[^}]*estado=\"ACTIVO\"")
                .contains("tripbus_procesar_posicion_segundos_count");
    }
}
