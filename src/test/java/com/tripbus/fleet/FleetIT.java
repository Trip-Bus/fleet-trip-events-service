package com.tripbus.fleet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.fleet.client.CatalogClient;
import com.tripbus.fleet.common.events.EventEnvelope;
import com.tripbus.fleet.common.redis.LeaderLock;
import com.tripbus.fleet.config.FleetProperties;
import com.tripbus.fleet.events.FleetEventsHandler;
import com.tripbus.fleet.repository.FleetItineraryRepository;
import com.tripbus.fleet.service.OutboxRelay;
import com.tripbus.fleet.service.RouteStopsCache;
import com.tripbus.fleet.service.TelemetryConsumer;
import com.tripbus.fleet.service.TelemetryService;
import com.tripbus.fleet.service.TripService;
import com.tripbus.fleet.simulator.GpsSimulator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    @Autowired
    private FleetItineraryRepository itineraryRepository;
    @Autowired
    private RouteStopsCache stopsCache;
    @Autowired
    private TripService tripService;
    @Autowired
    private LeaderLock leaderLock;
    @Autowired
    private Clock clock;

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
        publicar(itineraryId, busId, Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(8, ChronoUnit.HOURS));
    }

    private void publicar(UUID itinerary, UUID bus, Instant salida, Instant llegada) {
        publicar(itinerary, bus, salida, llegada, companyId);
    }

    private void publicar(UUID itinerary, UUID bus, Instant salida, Instant llegada, UUID empresa) {
        var payload = objectMapper.createObjectNode()
                .put("itineraryId", itinerary.toString()).put("busId", bus.toString())
                .put("routeId", routeId.toString())
                .put("origen", "Bogotá").put("destino", "Medellín")
                .put("fechaSalida", salida.toString())
                .put("fechaLlegada", llegada.toString());
        if (empresa != null) {
            payload.put("companyId", empresa.toString());
        }
        tx.executeWithoutResult(s -> handler.handle(
                new EventEnvelope(UUID.randomUUID().toString(), "ItineraryPublished", "test", Instant.now(), payload)));
    }

    private void cancelar(UUID itinerary) {
        var payload = objectMapper.createObjectNode().put("itineraryId", itinerary.toString());
        tx.executeWithoutResult(s -> handler.handle(
                new EventEnvelope(UUID.randomUUID().toString(), "ItineraryCancelled", "test", Instant.now(), payload)));
    }

    private void enviarPosicion(UUID bus, UUID itinerary, double lat, double lng) throws Exception {
        String itineraryJson = itinerary == null ? "" : ",\"itineraryId\":\"%s\"".formatted(itinerary);
        mvc.perform(post("/telemetry/position").with(conductor(companyId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"busId\":\"%s\"%s,\"lat\":%s,\"lng\":%s}".formatted(bus, itineraryJson, lat, lng)))
                .andExpect(status().isAccepted());
    }

    /** Hace de consumidor independiente: procesa todo lo encolado (idempotente por eventId). */
    private void procesarEncoladas() {
        for (MapRecord<String, Object, Object> r : redis.opsForStream().read(StreamOffset.fromStart("tripbus:telemetry:test"))) {
            Map<String, String> fields = new java.util.HashMap<>();
            r.getValue().forEach((k, v) -> fields.put(k.toString(), v.toString()));
            telemetryService.process(fields);
        }
    }

    private long guardadas(UUID bus) {
        return jdbc.queryForObject("select count(*) from bus_positions where bus_id = ?", Long.class, bus);
    }

    private long publicadas(UUID bus) {
        return jdbc.queryForObject("select count(*) from outbox where event_type = 'PositionUpdated' and payload like ?",
                Long.class, "%" + bus + "%");
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

    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
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
        // Otras pruebas también encolan en el mismo stream: se cuenta solo la posición de esta.
        assertThat(jdbc.queryForObject("select count(*) from bus_positions where lat = 5.0 and lng = -74.5",
                Long.class)).isEqualTo(1L);

        mvc.perform(get("/itineraries/{id}/bus-position", itineraryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_RUTA"))
                .andExpect(jsonPath("$.posicion.lat").value(5.0))
                .andExpect(jsonPath("$.paradas.length()").value(3));
        // En ruta, la posición del bus también es pública.
        mvc.perform(get("/buses/{id}/position", busId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lat").value(5.0));
        assertThat(publicadas(busId)).isGreaterThanOrEqualTo(1L);

        // Transactional outbox → bus de eventos con eventId = id del outbox. El relay programado también
        // corre y puede tomar las filas (SKIP LOCKED) antes que esta llamada: se espera a que haga commit.
        relay.relay();
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select count(*) from outbox where publicado_en is null", Long.class)).isZero();
            assertThat(redis.opsForStream().read(StreamOffset.fromStart("tripbus:events:test")))
                    .anyMatch(r -> "PositionUpdated".equals(r.getValue().get("type")));
        });
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

    private org.springframework.test.web.servlet.ResultActions enviarPosicion(RequestPostProcessor usuario, UUID bus,
                                                                             UUID itinerario) throws Exception {
        String itinerarioJson = itinerario != null ? ",\"itineraryId\":\"%s\"".formatted(itinerario) : "";
        return mvc.perform(post("/telemetry/position").with(usuario).contentType(MediaType.APPLICATION_JSON)
                .content("{\"busId\":\"%s\"%s,\"lat\":5.1,\"lng\":-74.6}".formatted(bus, itinerarioJson)));
    }

    @Test
    void soloSePublicanPosicionesDeBusesDeLaPropiaEmpresa() throws Exception {
        // Conductor de la empresa del bus, sin itinerario: el bus opera algún viaje de su empresa.
        enviarPosicion(conductor(companyId), busId, null).andExpect(status().isAccepted());

        // Conductor de otra empresa, con y sin itinerario.
        enviarPosicion(conductor(UUID.randomUUID()), busId, itineraryId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_YOUR_COMPANY"));
        enviarPosicion(conductor(UUID.randomUUID()), busId, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_YOUR_COMPANY"));

        // Token sin companyId.
        enviarPosicion(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "OPERADOR"))
                        .authorities(new SimpleGrantedAuthority("ROLE_OPERADOR")), busId, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_YOUR_COMPANY"));

        // itineraryId de otro bus.
        enviarPosicion(conductor(companyId), UUID.randomUUID(), itineraryId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BUS_ITINERARY_MISMATCH"));

        // Itinerario inexistente.
        enviarPosicion(conductor(companyId), busId, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ITINERARY_NOT_FOUND"));
    }

    @Test
    void elAdminPublicaDeCualquierBusPeroElItinerarioDebeCoincidir() throws Exception {
        enviarPosicion(admin(), UUID.randomUUID(), null).andExpect(status().isAccepted());
        enviarPosicion(admin(), UUID.randomUUID(), itineraryId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BUS_ITINERARY_MISMATCH"));
    }

    /** Viaje en curso, de la empresa indicada (null = sin empresa), sin novedades. */
    private UUID viajeEnCurso(UUID empresa) {
        UUID viaje = UUID.randomUUID();
        publicar(viaje, UUID.randomUUID(), Instant.now().minus(30, ChronoUnit.MINUTES),
                Instant.now().plus(8, ChronoUnit.HOURS), empresa);
        return viaje;
    }

    private org.springframework.test.web.servlet.ResultActions novedad(RequestPostProcessor usuario, UUID viaje,
                                                                      String tipo) throws Exception {
        return mvc.perform(post("/trips/{id}/events", viaje).with(usuario).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipo\":\"%s\",\"descripcion\":\"Novedad %s\"}".formatted(tipo, tipo)));
    }

    private long novedades(UUID viaje) {
        return jdbc.queryForObject("select count(*) from trip_events where itinerary_id = ?", Long.class, viaje);
    }

    @Test
    void elConductorReportaNovedadesSoloDeViajesDeSuEmpresa() throws Exception {
        UUID viaje = viajeEnCurso(companyId);
        novedad(conductor(companyId), viaje, "INICIO").andExpect(status().isCreated());

        mvc.perform(post("/trips/{id}/events", viaje).with(conductor(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"RETRASO\",\"descripcion\":\"Derrumbe en la vía\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/trips/{id}/events", viaje).with(conductor(companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"RETRASO\",\"descripcion\":\"Derrumbe en la vía\",\"detalle\":{\"minutos\":40}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("RETRASO"));

        mvc.perform(get("/trips/{id}/timeline", viaje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tipo").value("INICIO"))
                .andExpect(jsonPath("$[?(@.tipo == 'RETRASO')].detalle.minutos").value(hasItem(40)));
        assertThat(jdbc.queryForObject("select count(*) from outbox where event_type = 'TripEventReported'", Long.class))
                .isGreaterThanOrEqualTo(1L);
    }

    @Test
    void lasNovedadesRespetanElOrdenInicioFin() throws Exception {
        UUID viaje = viajeEnCurso(companyId);
        // Nada antes de INICIO.
        novedad(conductor(companyId), viaje, "FIN")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_TRIP_EVENT"));
        novedad(conductor(companyId), viaje, "RETRASO")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_TRIP_EVENT"));

        novedad(conductor(companyId), viaje, "INICIO").andExpect(status().isCreated());
        novedad(conductor(companyId), viaje, "INICIO")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_TRIP_EVENT"));
        novedad(conductor(companyId), viaje, "LLEGADA_PARADA").andExpect(status().isCreated());
        novedad(conductor(companyId), viaje, "DESVIO").andExpect(status().isCreated());
        novedad(conductor(companyId), viaje, "FIN").andExpect(status().isCreated());

        // Después de FIN: ni otro FIN ni más novedades.
        novedad(conductor(companyId), viaje, "FIN")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_TRIP_EVENT"));
        novedad(admin(), viaje, "RETRASO").andExpect(status().isConflict());
        assertThat(novedades(viaje)).isEqualTo(4L);
    }

    @Test
    void sinEmpresaEnElTokenNiEnElViajeSoloElAdminReporta() throws Exception {
        UUID viaje = viajeEnCurso(null);
        RequestPostProcessor operadorSinEmpresa = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("role", "OPERADOR"))
                .authorities(new SimpleGrantedAuthority("ROLE_OPERADOR"));

        novedad(operadorSinEmpresa, viaje, "INICIO")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_YOUR_COMPANY"));
        // Con empresa en el token tampoco: el viaje no es de ninguna empresa.
        novedad(conductor(companyId), viaje, "INICIO")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_YOUR_COMPANY"));
        novedad(admin(), viaje, "INICIO").andExpect(status().isCreated());
        assertThat(novedades(viaje)).isEqualTo(1L);
    }

    @Test
    void elMismoIdempotencyKeyDevuelveLaMismaNovedad() throws Exception {
        UUID viaje = viajeEnCurso(companyId);
        String body = "{\"tipo\":\"INICIO\",\"descripcion\":\"Salimos\"}";
        String primera = mvc.perform(post("/trips/{id}/events", viaje).with(conductor(companyId))
                        .header("Idempotency-Key", "inicio-1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(primera).get("tripEventId").asText();

        // El reintento no choca con la regla de un solo INICIO: devuelve la novedad ya creada.
        mvc.perform(post("/trips/{id}/events", viaje).with(conductor(companyId))
                        .header("Idempotency-Key", "inicio-1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tripEventId").value(id))
                .andExpect(jsonPath("$.tipo").value("INICIO"));
        assertThat(novedades(viaje)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from outbox where event_type = 'TripEventReported' and payload like ?",
                Long.class, "%" + id + "%")).isEqualTo(1L);

        // Otro key sí es otra novedad (y aquí rompe el orden).
        mvc.perform(post("/trips/{id}/events", viaje).with(conductor(companyId))
                        .header("Idempotency-Key", "inicio-2").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mvc.perform(post("/trips/{id}/events", viaje).with(conductor(companyId))
                        .header("Idempotency-Key", "x".repeat(101)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipo\":\"RETRASO\",\"descripcion\":\"Trancón\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_IDEMPOTENCY_KEY"));
        // Un key ajeno no revela novedades de otra empresa.
        mvc.perform(post("/trips/{id}/events", viaje).with(conductor(UUID.randomUUID()))
                        .header("Idempotency-Key", "inicio-1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    // ---- US-038: reintento de la telemetría que falla ----

    private static final String STREAM_REINTENTOS = "tripbus:telemetry:retry-test";
    private static final String DLQ_PRUEBA = "tripbus:telemetry:dlq-test";

    @Autowired
    private RedisConnectionFactory connectionFactory;

    /** Consumidor real sobre un stream propio, con 200 ms de inactividad antes de reintentar. */
    private TelemetryConsumer consumidor(TelemetryService servicio) {
        redis.delete(List.of(STREAM_REINTENTOS, DLQ_PRUEBA));
        FleetProperties props = new FleetProperties(STREAM_REINTENTOS, "fleet-telemetry", Duration.ofMinutes(10),
                null, false, null, null, DLQ_PRUEBA, Duration.ofMillis(200), 5);
        TelemetryConsumer consumer = new TelemetryConsumer(connectionFactory, redis, servicio, props, leaderLock, true);
        consumer.start();
        return consumer;
    }

    private String encolar(UUID bus) {
        String eventId = UUID.randomUUID().toString();
        redis.opsForStream().add(StreamRecords.newRecord().in(STREAM_REINTENTOS).ofMap(Map.of(
                "eventId", eventId, "busId", bus.toString(), "lat", "5.5", "lng", "-74.9",
                "timestamp", Instant.now().toString())));
        return eventId;
    }

    private long pendientes() {
        return redis.opsForStream().pending(STREAM_REINTENTOS, "fleet-telemetry").getTotalPendingMessages();
    }

    @Test
    void unFalloAlProcesarNoHaceAckYElReintentoGuardaLaPosicionUnaSolaVez() {
        UUID bus = UUID.randomUUID();
        AtomicInteger intentos = new AtomicInteger();
        TelemetryService inestable = mock(TelemetryService.class);
        // La primera entrega guarda la posición y luego falla (p. ej. se cae la conexión antes del ACK).
        doAnswer(inv -> {
            telemetryService.process(inv.getArgument(0));
            if (intentos.incrementAndGet() == 1) {
                throw new IllegalStateException("fallo simulado");
            }
            return null;
        }).when(inestable).process(anyMap());
        TelemetryConsumer consumer = consumidor(inestable);
        try {
            encolar(bus);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(intentos.get()).isEqualTo(1);
                assertThat(pendientes()).as("sin ACK tras el fallo").isEqualTo(1L);
            });

            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
                consumer.retryPending();
                assertThat(pendientes()).isZero();
            });
            assertThat(intentos.get()).isEqualTo(2);
            assertThat(guardadas(bus)).as("el reintento no duplica la posición").isEqualTo(1L);
            assertThat(redis.opsForStream().size(DLQ_PRUEBA)).isZero();
        } finally {
            consumer.stop();
        }
    }

    @Test
    void trasCincoEntregasFallidasLaPosicionVaALaDlq() {
        UUID bus = UUID.randomUUID();
        TelemetryService roto = mock(TelemetryService.class);
        doThrow(new IllegalStateException("fallo permanente")).when(roto).process(anyMap());
        TelemetryConsumer consumer = consumidor(roto);
        try {
            String eventId = encolar(bus);
            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
                consumer.retryPending();
                assertThat(redis.opsForStream().range(DLQ_PRUEBA, org.springframework.data.domain.Range.unbounded()))
                        .singleElement()
                        .satisfies(r -> {
                            assertThat(r.getValue()).containsEntry("eventId", eventId).containsEntry("deliveries", "5");
                        });
            });
            assertThat(pendientes()).as("ACK al mover a la DLQ").isZero();
            verify(roto, times(5)).process(anyMap());
            assertThat(guardadas(bus)).isZero();
        } finally {
            consumer.stop();
        }
    }

    @Test
    void antesDeLaVentanaNoSeMuestraNiSePublicaLaPosicion() throws Exception {
        UUID viaje = UUID.randomUUID();
        UUID bus = UUID.randomUUID();
        publicar(viaje, bus, Instant.now().plus(2, ChronoUnit.HOURS), Instant.now().plus(10, ChronoUnit.HOURS));

        enviarPosicion(bus, viaje, 4.6, -74.1);
        procesarEncoladas();

        assertThat(guardadas(bus)).as("se guarda para auditoría").isEqualTo(1L);
        assertThat(publicadas(bus)).as("no va al outbox fuera de la ventana").isZero();
        mvc.perform(get("/itineraries/{id}/bus-position", viaje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PROGRAMADO"))
                .andExpect(jsonPath("$.posicion").value(nullValue()))
                .andExpect(jsonPath("$.paradas.length()").value(3));
        mvc.perform(get("/buses/{id}/position", bus))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_TRIP"));
    }

    @Test
    void dentroDeLaVentanaAntesDeSalirLaPosicionYaEsVisible() throws Exception {
        UUID viaje = UUID.randomUUID();
        UUID bus = UUID.randomUUID();
        publicar(viaje, bus, Instant.now().plus(20, ChronoUnit.MINUTES), Instant.now().plus(8, ChronoUnit.HOURS));

        enviarPosicion(bus, null, 4.65, -74.11); // sin itinerario: se resuelve por el bus
        procesarEncoladas();

        assertThat(publicadas(bus)).isEqualTo(1L);
        mvc.perform(get("/buses/{id}/position", bus))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lat").value(4.65));
        mvc.perform(get("/itineraries/{id}/bus-position", viaje))
                .andExpect(jsonPath("$.estado").value("PROGRAMADO"))
                .andExpect(jsonPath("$.posicion").value(nullValue())); // la posición no trae este itinerario
    }

    @Test
    void despuesDeLlegarEsFinalizadoAunqueHayaPosicionEnCache() throws Exception {
        UUID viaje = UUID.randomUUID();
        UUID bus = UUID.randomUUID();
        publicar(viaje, bus, Instant.now().minus(9, ChronoUnit.HOURS), Instant.now().minus(10, ChronoUnit.MINUTES));

        enviarPosicion(bus, viaje, 6.27, -75.57);
        procesarEncoladas();

        // Aún dentro de los 30 min posteriores: la posición se ve, pero el viaje ya terminó.
        assertThat(publicadas(bus)).isEqualTo(1L);
        mvc.perform(get("/itineraries/{id}/bus-position", viaje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("FINALIZADO"))
                .andExpect(jsonPath("$.posicion.lat").value(6.27));
    }

    @Test
    void despuesDeLaVentanaLaPosicionEsNullYNoSePublica() throws Exception {
        UUID viaje = UUID.randomUUID();
        UUID bus = UUID.randomUUID();
        publicar(viaje, bus, Instant.now().minus(10, ChronoUnit.HOURS), Instant.now().minus(1, ChronoUnit.HOURS));

        enviarPosicion(bus, viaje, 6.27, -75.57);
        procesarEncoladas();

        assertThat(guardadas(bus)).isEqualTo(1L);
        assertThat(publicadas(bus)).isZero();
        assertThat(redis.opsForValue().get("fleet:itinerary:" + viaje + ":last")).as("hay caché").isNotNull();
        mvc.perform(get("/itineraries/{id}/bus-position", viaje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("FINALIZADO"))
                .andExpect(jsonPath("$.posicion").value(nullValue()));
        mvc.perform(get("/buses/{id}/position", bus))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_TRIP"));
    }

    @Test
    void unViajeCanceladoNuncaMuestraNiPublicaLaPosicion() throws Exception {
        UUID viaje = UUID.randomUUID();
        UUID bus = UUID.randomUUID();
        publicar(viaje, bus, Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(5, ChronoUnit.HOURS));
        cancelar(viaje);

        enviarPosicion(bus, viaje, 5.2, -74.73);
        procesarEncoladas();

        assertThat(guardadas(bus)).isEqualTo(1L);
        assertThat(publicadas(bus)).isZero();
        mvc.perform(get("/itineraries/{id}/bus-position", viaje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CANCELADO"))
                .andExpect(jsonPath("$.posicion").value(nullValue()));
        mvc.perform(get("/buses/{id}/position", bus))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_TRIP"));
    }

    @Test
    void unBusSinViajesNoExponeSuPosicion() throws Exception {
        UUID bus = UUID.randomUUID();
        // Ninguna empresa opera este bus: solo un ADMIN puede publicar su posición (US-038).
        enviarPosicion(admin(), bus, null).andExpect(status().isAccepted());
        procesarEncoladas();

        assertThat(guardadas(bus)).isEqualTo(1L);
        assertThat(publicadas(bus)).isZero();
        mvc.perform(get("/buses/{id}/position", bus))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_TRIP"));
    }

    @Test
    void elSimuladorSigueMoviendoLosBusesEnRuta() throws Exception {
        UUID viaje = UUID.randomUUID();
        UUID bus = UUID.randomUUID();
        publicar(viaje, bus, Instant.now().minus(30, ChronoUnit.MINUTES), Instant.now().plus(8, ChronoUnit.HOURS));

        new GpsSimulator(itineraryRepository, stopsCache, telemetryService, tripService, leaderLock, tx, clock)
                .simulate();
        procesarEncoladas();

        assertThat(publicadas(bus)).isEqualTo(1L);
        mvc.perform(get("/itineraries/{id}/bus-position", viaje))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_RUTA"))
                .andExpect(jsonPath("$.posicion.busId").value(bus.toString()));
        mvc.perform(get("/trips/{id}/timeline", viaje))
                .andExpect(jsonPath("$[0].tipo").value("INICIO"));

        // Las novedades del simulador cumplen el mismo orden: un INICIO manual ya no es posible.
        novedad(conductor(companyId), viaje, "INICIO").andExpect(status().isConflict());
        novedad(conductor(companyId), viaje, "RETRASO").andExpect(status().isCreated());
    }

    @Test
    void elSimuladorNoRepiteElInicioReportadoNiAnunciaParadasTrasElFin() throws Exception {
        UUID viaje = UUID.randomUUID();
        UUID otro = UUID.randomUUID();
        // Ya pasó por Honda (minuto 210): el simulador anuncia esa parada.
        publicar(viaje, UUID.randomUUID(), Instant.now().minus(4, ChronoUnit.HOURS), Instant.now().plus(5, ChronoUnit.HOURS));
        publicar(otro, UUID.randomUUID(), Instant.now().minus(4, ChronoUnit.HOURS), Instant.now().plus(5, ChronoUnit.HOURS));
        novedad(conductor(companyId), viaje, "INICIO").andExpect(status().isCreated());
        novedad(conductor(companyId), otro, "INICIO").andExpect(status().isCreated());
        novedad(conductor(companyId), otro, "FIN").andExpect(status().isCreated());

        new GpsSimulator(itineraryRepository, stopsCache, telemetryService, tripService, leaderLock, tx, clock)
                .simulate();

        mvc.perform(get("/trips/{id}/timeline", viaje))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].tipo").value("INICIO"))
                .andExpect(jsonPath("$[0].origen").value("USUARIO"))
                .andExpect(jsonPath("$[1].tipo").value("LLEGADA_PARADA"))
                .andExpect(jsonPath("$[1].origen").value("SIMULADOR"));
        assertThat(novedades(otro)).as("tras FIN el simulador no agrega nada").isEqualTo(2L);
        assertThat(jdbc.queryForObject("select sim_ultima_parada from fleet_itineraries where itinerary_id = ?",
                Integer.class, viaje)).isEqualTo(1);
    }
}
