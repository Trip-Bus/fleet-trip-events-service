package com.tripbus.fleet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripbus.fleet.client.CatalogClient;
import com.tripbus.fleet.common.events.EventEnvelope;
import com.tripbus.fleet.common.redis.LeaderLock;
import com.tripbus.fleet.events.CatalogSyncHandler;
import com.tripbus.fleet.events.FleetEventsHandler;
import com.tripbus.fleet.repository.FleetItineraryRepository;
import com.tripbus.fleet.service.DriverTrackingService;
import com.tripbus.fleet.service.OutboxRelay;
import com.tripbus.fleet.service.RouteStopsCache;
import com.tripbus.fleet.service.TelemetryService;
import com.tripbus.fleet.service.TripService;
import com.tripbus.fleet.simulator.GpsSimulator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US-177 (detección automática) y US-180 (ETA por parada) contra PostgreSQL y Redis reales
 * (Testcontainers). Las posiciones se procesan como lo hace el consumidor (TelemetryService.process) y,
 * para recorrer un viaje de horas en segundos, llevan timestamps del pasado. Ruta Bogotá → Chocontá → Tunja.
 */
@SpringBootTest(properties = {"tripbus.jobs.tracking-status-ms=3600000", "TELEMETRY_STREAM=tripbus:telemetry:test"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class TripAnalysisIT {

    private static final double[] BOGOTA = {4.6584, -74.1030};
    private static final double[] CHOCONTA = {5.1447, -73.6858};
    private static final double[] TUNJA = {5.5446, -73.3573};

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    // Arrancados aquí (sin @Container) para que sigan vivos mientras @DirtiesContext cierra el contexto.
    static {
        postgres.start();
        redisContainer.start();
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private FleetEventsHandler fleetHandler;
    @Autowired
    private CatalogSyncHandler catalogHandler;
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
    private DriverTrackingService trackingService;
    @Autowired
    private LeaderLock leaderLock;
    @Autowired
    private Clock clock;

    @MockitoBean
    private CatalogClient catalogClient;

    private final UUID ruta = UUID.randomUUID();

    @BeforeAll
    void preparar() {
        flyway.clean();
        flyway.migrate();
        redis.delete(List.of("tripbus:telemetry:test", "tripbus:events:test"));
        ObjectNode payload = objectMapper.createObjectNode().put("routeId", ruta.toString());
        var paradas = payload.putArray("paradas");
        paradas.addObject().put("orden", 1).put("nombre", "Bogotá").put("lat", BOGOTA[0]).put("lng", BOGOTA[1])
                .put("minutosDesdeSalida", 0);
        paradas.addObject().put("orden", 2).put("nombre", "Chocontá").put("lat", CHOCONTA[0]).put("lng", CHOCONTA[1])
                .put("minutosDesdeSalida", 80);
        paradas.addObject().put("orden", 3).put("nombre", "Tunja").put("lat", TUNJA[0]).put("lng", TUNJA[1])
                .put("minutosDesdeSalida", 150);
        evento("RouteCreated", payload);
    }

    // ---------- utilidades ----------

    private record Viaje(UUID itineraryId, UUID busId, Instant salida) {
    }

    private void evento(String tipo, ObjectNode payload) {
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID().toString(), tipo, "catalog", Instant.now(), payload);
        tx.executeWithoutResult(s -> {
            if (catalogHandler.eventTypes().contains(tipo)) {
                catalogHandler.handle(envelope);
            } else {
                fleetHandler.handle(envelope);
            }
        });
    }

    /** Viaje por la ruta que salió hace {@code minutosAtras} y llega dentro de 1 h. */
    private Viaje viaje(long minutosAtras) {
        Instant salida = Instant.now().minus(minutosAtras, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        Viaje viaje = new Viaje(UUID.randomUUID(), UUID.randomUUID(), salida);
        evento("ItineraryPublished", objectMapper.createObjectNode()
                .put("itineraryId", viaje.itineraryId().toString()).put("busId", viaje.busId().toString())
                .put("routeId", ruta.toString()).put("companyId", UUID.randomUUID().toString())
                .put("origen", "Bogotá").put("destino", "Tunja")
                .put("fechaSalida", salida.toString())
                .put("fechaLlegada", Instant.now().plus(1, ChronoUnit.HOURS).toString()));
        return viaje;
    }

    /** Procesa una posición como el consumidor de telemetría (a los minutos dados desde la salida). */
    private void posicion(Viaje viaje, double lat, double lng, long minutosDesdeSalida) {
        Map<String, String> fields = new HashMap<>();
        fields.put("eventId", UUID.randomUUID().toString());
        fields.put("busId", viaje.busId().toString());
        fields.put("itineraryId", viaje.itineraryId().toString());
        fields.put("lat", String.valueOf(lat));
        fields.put("lng", String.valueOf(lng));
        fields.put("timestamp", viaje.salida().plus(minutosDesdeSalida, ChronoUnit.MINUTES).toString());
        telemetryService.process(fields);
    }

    /** Punto a la fracción f del tramo Bogotá → Chocontá, corrido dLng grados al este. */
    private void enTramo(Viaje viaje, double f, double dLng, long minutos) {
        posicion(viaje, BOGOTA[0] + f * (CHOCONTA[0] - BOGOTA[0]), BOGOTA[1] + f * (CHOCONTA[1] - BOGOTA[1]) + dLng,
                minutos);
    }

    private List<JsonNode> novedades(Viaje viaje, String tipo) {
        return jdbc.queryForList("select payload from trip_events where itinerary_id = ? and tipo = ? order by ocurrido_en",
                        String.class, viaje.itineraryId(), tipo).stream().map(this::json).toList();
    }

    private long outbox(String tipo, Viaje viaje) {
        return jdbc.queryForObject("select count(*) from outbox where event_type = ? and payload like ?", Long.class,
                tipo, "%" + viaje.itineraryId() + "%");
    }

    private JsonNode json(String texto) {
        try {
            return objectMapper.readTree(texto);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- US-177 + US-180 ----------

    @Test
    @Order(1)
    void llegaACadaParadaSeRetrasaSeDesviaYVuelve() throws Exception {
        Viaje viaje = viaje(180);

        // En la terminal antes de salir: llegada a la primera parada, una sola vez.
        posicion(viaje, BOGOTA[0], BOGOTA[1], -5);
        posicion(viaje, BOGOTA[0], BOGOTA[1], 1);
        assertThat(novedades(viaje, "LLEGADA_PARADA")).hasSize(1);

        // Casi sin avanzar: a los 12 min ya va 12 min tarde → RETRASO; a los 20 (~19) no se repite;
        // a los 30 (~28, 15 más que el aviso anterior) sí.
        enTramo(viaje, 0.001, 0, 12);
        enTramo(viaje, 0.01, 0, 20);
        enTramo(viaje, 0.02, 0, 30);
        List<JsonNode> retrasos = novedades(viaje, "RETRASO");
        assertThat(retrasos).hasSize(2);
        assertThat(retrasos.get(0).path("minutos").asLong()).isEqualTo(12);
        assertThat(retrasos.get(1).path("minutos").asLong()).isBetween(27L, 29L);
        assertThat(retrasos).allMatch(r -> "AUTOMATICO".equals(r.path("origen").asText()));

        // Desvío: a ~5 km de la ruta; la alerta sale en la tercera lectura seguida y no se repite.
        enTramo(viaje, 0.05, 0.05, 31);
        enTramo(viaje, 0.05, 0.05, 32);
        assertThat(novedades(viaje, "DESVIO")).isEmpty();
        enTramo(viaje, 0.05, 0.05, 33);
        enTramo(viaje, 0.05, 0.05, 34);
        assertThat(novedades(viaje, "DESVIO")).hasSize(1);
        assertThat(novedades(viaje, "DESVIO").get(0).path("origen").asText()).isEqualTo("AUTOMATICO");
        // Vuelve a la ruta y se desvía otra vez: con la alerta abierta no se crea otra.
        enTramo(viaje, 0.1, 0, 40);
        enTramo(viaje, 0.12, 0.05, 41);
        enTramo(viaje, 0.12, 0.05, 42);
        enTramo(viaje, 0.12, 0.05, 43);
        assertThat(novedades(viaje, "DESVIO")).hasSize(1);
        assertThat(jdbc.queryForList("select gravedad from trip_alerts where itinerary_id = ? and tipo = 'DESVIO'",
                String.class, viaje.itineraryId())).containsExactly("ALTA");
        assertThat(outbox("TripAlertRaised", viaje)).isEqualTo(1L);

        // Llega a Chocontá y a Tunja.
        posicion(viaje, CHOCONTA[0], CHOCONTA[1], 90);
        mvc.perform(get("/itineraries/{id}/eta", viaje.itineraryId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paradas.length()").value(3))
                .andExpect(jsonPath("$.paradas[1].alcanzada").value(true))
                .andExpect(jsonPath("$.paradas[2].alcanzada").value(false))
                .andExpect(jsonPath("$.paradas[2].programada").value(viaje.salida().plus(150, ChronoUnit.MINUTES)
                        .toString()))
                .andExpect(jsonPath("$.paradas[2].estimada").isNotEmpty());
        posicion(viaje, TUNJA[0] + 0.001, TUNJA[1], 160); // ~110 m: dentro de los 300 m de la última
        List<JsonNode> llegadas = novedades(viaje, "LLEGADA_PARADA");
        assertThat(llegadas).extracting(l -> l.path("orden").asInt()).containsExactly(1, 2, 3);
        assertThat(llegadas).allMatch(l -> "AUTOMATICO".equals(l.path("origen").asText()));
        assertThat(jdbc.queryForObject("select count(*) from trip_events where itinerary_id = ? and origen = 'AUTOMATICO'",
                Long.class, viaje.itineraryId())).isEqualTo(6L); // 3 llegadas, 2 retrasos y 1 desvío
        assertThat(jdbc.queryForObject("select ultima_parada_real from fleet_itineraries where itinerary_id = ?",
                Integer.class, viaje.itineraryId())).isEqualTo(3);
        mvc.perform(get("/itineraries/{id}/bus-position", viaje.itineraryId()))
                .andExpect(jsonPath("$.eta.paradas[2].alcanzada").value(true))
                .andExpect(jsonPath("$.eta.retrasoMinutos").value(10));

        // EtaUpdated: todo lo anterior pasó en menos de 30 s → se publicó una sola vez.
        assertThat(outbox("EtaUpdated", viaje)).isEqualTo(1L);
        // Pasados 30 s desde la última publicación (se simula en Redis), un cambio sí se publica…
        redis.opsForValue().set("fleet:itinerary:" + viaje.itineraryId() + ":eta-publicada-en",
                String.valueOf(Instant.now().minusSeconds(31).toEpochMilli()));
        posicion(viaje, TUNJA[0], TUNJA[1], 165);
        assertThat(outbox("EtaUpdated", viaje)).isEqualTo(2L);
        // …y sin cambios (o antes de 30 s) no.
        posicion(viaje, TUNJA[0], TUNJA[1], 166);
        assertThat(outbox("EtaUpdated", viaje)).isEqualTo(2L);
        JsonNode eta = json(jdbc.queryForObject("select payload from outbox where event_type = 'EtaUpdated' "
                + "and payload like ? order by creado_en desc limit 1", String.class, "%" + viaje.itineraryId() + "%"));
        assertThat(eta.fieldNames()).toIterable().containsExactly("itineraryId", "companyId", "retrasoMinutos",
                "paradas");
        assertThat(eta.path("paradas").get(0).fieldNames()).toIterable()
                .containsExactly("orden", "nombre", "programada", "estimada", "alcanzada");

        relay.relay();
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(redis.opsForStream().read(StreamOffset.fromStart("tripbus:events:test")))
                        .anyMatch(r -> "EtaUpdated".equals(r.getValue().get("type"))));
    }

    @Test
    @Order(2)
    void fueraDeLaVentanaOCanceladoNoHayEta() throws Exception {
        // Sin posiciones todavía.
        Viaje sinPosiciones = viaje(10);
        mvc.perform(get("/itineraries/{id}/eta", sinPosiciones.itineraryId()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NO_ETA"));

        // Con ETA calculada pero cancelado.
        Viaje cancelado = viaje(10);
        enTramo(cancelado, 0.1, 0, 10);
        mvc.perform(get("/itineraries/{id}/eta", cancelado.itineraryId())).andExpect(status().isOk());
        evento("ItineraryCancelled", objectMapper.createObjectNode().put("itineraryId", cancelado.itineraryId().toString()));
        mvc.perform(get("/itineraries/{id}/eta", cancelado.itineraryId()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NO_ETA"));
        mvc.perform(get("/itineraries/{id}/bus-position", cancelado.itineraryId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.eta").doesNotExist());
        // Las posiciones de un viaje cancelado ya no se analizan.
        long antes = outbox("EtaUpdated", cancelado);
        enTramo(cancelado, 0.2, 0, 20);
        assertThat(outbox("EtaUpdated", cancelado)).isEqualTo(antes);

        mvc.perform(get("/itineraries/{id}/eta", UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TRIP_NOT_FOUND"));
    }

    @Test
    @Order(3)
    void unFalloAlAnalizarNoPierdeLaPosicion() {
        Viaje viaje = viaje(10);
        // Una llave de Redis con un tipo inesperado hace fallar el análisis…
        redis.opsForValue().set("fleet:itinerary:" + viaje.itineraryId() + ":recientes", "no-es-una-lista");

        enTramo(viaje, 0.1, 0, 10);

        // …pero la posición quedó guardada y publicada.
        assertThat(jdbc.queryForObject("select count(*) from bus_positions where itinerary_id = ?", Long.class,
                viaje.itineraryId())).isEqualTo(1L);
        assertThat(outbox("PositionUpdated", viaje)).isEqualTo(1L);
    }

    /** Al final: el simulador mueve todos los viajes en curso de esta clase. */
    @Test
    @Order(4)
    void elSimuladorYLaDeteccionNoDuplicanLlegadas() {
        // Chocontá (80 min) ya debió pasar en ambos viajes.
        Viaje primeroDeteccion = viaje(85);
        Viaje primeroSimulador = viaje(85);

        posicion(primeroDeteccion, CHOCONTA[0], CHOCONTA[1], 85);
        new GpsSimulator(itineraryRepository, stopsCache, telemetryService, tripService, trackingService, leaderLock,
                tx, clock).simulate();
        posicion(primeroSimulador, CHOCONTA[0], CHOCONTA[1], 85);

        List<JsonNode> deteccion = novedades(primeroDeteccion, "LLEGADA_PARADA");
        assertThat(deteccion).hasSize(1);
        assertThat(deteccion.get(0).path("origen").asText()).isEqualTo("AUTOMATICO");
        List<JsonNode> simulador = novedades(primeroSimulador, "LLEGADA_PARADA");
        assertThat(simulador).hasSize(1);
        assertThat(simulador.get(0).path("orden").asInt()).isEqualTo(2);
        assertThat(simulador.get(0).has("origen")).isFalse(); // la generó el simulador
        // Aun así la detección avanzó: la siguiente pendiente ya es Tunja.
        assertThat(jdbc.queryForObject("select ultima_parada_real from fleet_itineraries where itinerary_id = ?",
                Integer.class, primeroSimulador.itineraryId())).isEqualTo(2);
    }
}
