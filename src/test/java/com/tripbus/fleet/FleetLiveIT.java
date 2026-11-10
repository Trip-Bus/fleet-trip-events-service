package com.tripbus.fleet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.tripbus.fleet.client.CatalogClient;
import com.tripbus.fleet.client.IdentityClient;
import com.tripbus.fleet.common.events.EventEnvelope;
import com.tripbus.fleet.events.CatalogSyncHandler;
import com.tripbus.fleet.events.FleetEventsHandler;
import com.tripbus.fleet.service.OutboxRelay;
import com.tripbus.fleet.service.TelemetryService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US-178 (mapa de la flota del operador) contra PostgreSQL y Redis reales (Testcontainers), con
 * identity simulado por HTTP (WireMock). Las posiciones se procesan como lo hace el consumidor de
 * telemetría, así que la última posición y la ETA del mapa salen de Redis como en QA.
 */
@SpringBootTest(properties = {"tripbus.jobs.tracking-status-ms=3600000"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class FleetLiveIT {

    private static final double[] BOGOTA = {4.6584, -74.1030};
    private static final double[] CHOCONTA = {5.1447, -73.6858};
    private static final double[] TUNJA = {5.5446, -73.3573};

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final WireMockServer identity = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    // Arrancados aquí (sin @Container) para que sigan vivos mientras @DirtiesContext cierra el contexto.
    static {
        identity.start();
        postgres.start();
        redisContainer.start();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("tripbus.services.identity-url", identity::baseUrl);
        registry.add("TELEMETRY_STREAM", () -> "tripbus:telemetry:test");
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
    private IdentityClient identityClient;
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

    private final UUID ruta = UUID.randomUUID();
    private final UUID empresaA = UUID.randomUUID();
    private final UUID empresaB = UUID.randomUUID();
    private final UUID conductorAna = UUID.randomUUID();

    // Viajes de la empresa A (ver flotaDeLaEmpresaConViajesEnVariosEstados).
    private UUID enRuta;
    private UUID enPausa;
    private UUID sinSenal;
    private UUID porSalir;
    private UUID fueraDeVentana;
    private UUID yaLlego;
    private UUID cancelado;
    private UUID deOtraEmpresa;

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

        usuario(conductorAna, "Ana Conductora");
        // La primera petición a WireMock (en frío) puede pasar de los 800 ms de lectura del cliente:
        // se calienta antes para que el mapa no la tome por identity caído.
        UUID calentar = UUID.randomUUID();
        usuario(calentar, "Calentamiento");
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
                .until(() -> identityClient.user(calentar).isPresent());
    }

    @AfterAll
    void detenerIdentity() {
        identity.stop();
    }

    // ---------- utilidades ----------

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

    /** Publica un viaje de la ruta que sale a los minutos dados desde ahora (negativo: ya salió). */
    private UUID viaje(UUID empresa, long salidaEnMin, long llegadaEnMin) {
        UUID id = UUID.randomUUID();
        Instant ahora = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        evento("ItineraryPublished", objectMapper.createObjectNode()
                .put("itineraryId", id.toString()).put("busId", UUID.randomUUID().toString())
                .put("routeId", ruta.toString()).put("companyId", empresa.toString())
                .put("origen", "Bogotá").put("destino", "Tunja")
                .put("fechaSalida", ahora.plus(salidaEnMin, ChronoUnit.MINUTES).toString())
                .put("fechaLlegada", ahora.plus(llegadaEnMin, ChronoUnit.MINUTES).toString()));
        return id;
    }

    private void sesion(UUID viaje, String estado) {
        jdbc.update("insert into tracking_sessions (itinerary_id, estado, actualizado_en) values (?, ?, ?)",
                viaje, estado, Timestamp.from(Instant.now()));
    }

    private void iniciar(UUID viaje) {
        jdbc.update("update fleet_itineraries set iniciado_en = ? where itinerary_id = ?",
                Timestamp.from(Instant.now().minus(15, ChronoUnit.MINUTES)), viaje);
    }

    private void asignar(UUID viaje, UUID conductor, String rol) {
        jdbc.update("""
                insert into trip_assignments (id, itinerary_id, conductor_id, rol, asignado_por, asignado_en)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), viaje, conductor, rol, UUID.randomUUID(), Timestamp.from(Instant.now()));
    }

    private void alerta(UUID viaje, UUID empresa, String tipo, String gravedad, String estado) {
        jdbc.update("""
                insert into trip_alerts (id, itinerary_id, company_id, bus_id, tipo, gravedad, estado, creado_en)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), viaje, empresa, UUID.randomUUID(), tipo, gravedad, estado,
                Timestamp.from(Instant.now()));
    }

    private UUID busDe(UUID viaje) {
        return jdbc.queryForObject("select bus_id from fleet_itineraries where itinerary_id = ?", UUID.class, viaje);
    }

    private Instant salidaDe(UUID viaje) {
        return jdbc.queryForObject("select fecha_salida from fleet_itineraries where itinerary_id = ?",
                Timestamp.class, viaje).toInstant();
    }

    /** Procesa una posición como el consumidor de telemetría (a los minutos dados desde la salida). */
    private void posicion(UUID viaje, double lat, double lng, long minutosDesdeSalida) {
        Map<String, String> fields = new HashMap<>();
        fields.put("eventId", UUID.randomUUID().toString());
        fields.put("busId", busDe(viaje).toString());
        fields.put("itineraryId", viaje.toString());
        fields.put("lat", String.valueOf(lat));
        fields.put("lng", String.valueOf(lng));
        fields.put("velocidad", "35.5");
        fields.put("rumbo", "45.0");
        fields.put("timestamp", salidaDe(viaje).plus(minutosDesdeSalida, ChronoUnit.MINUTES).toString());
        telemetryService.process(fields);
    }

    private void usuario(UUID id, String nombre) {
        identity.stubFor(WireMock.get(urlEqualTo("/internal/users/" + id)).willReturn(okJson("""
                {"id":"%s","nombre":"%s","rol":"CONDUCTOR","companyId":null,"activo":true}
                """.formatted(id, nombre))));
    }

    private JsonNode ultimoPayload(String tipo, UUID viaje) throws Exception {
        return objectMapper.readTree(jdbc.queryForObject("select payload from outbox where event_type = ? "
                + "and payload like ? order by creado_en desc limit 1", String.class, tipo, "%" + viaje + "%"));
    }

    private static RequestPostProcessor operador(UUID company) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "OPERADOR")
                        .claim("companyId", company.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_OPERADOR"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor conductor() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "CONDUCTOR"))
                .authorities(new SimpleGrantedAuthority("ROLE_CONDUCTOR"));
    }

    private static String viajeConId(UUID id) {
        return "$.viajes[?(@.itineraryId == '" + id + "')]";
    }

    // ---------- US-178 ----------

    @Test
    @Order(1)
    void flotaDeLaEmpresaConViajesEnVariosEstados() throws Exception {
        enRuta = viaje(empresaA, -20, 130);
        enPausa = viaje(empresaA, -10, 140);
        sinSenal = viaje(empresaA, -40, 110);
        porSalir = viaje(empresaA, 20, 170);          // dentro de los 30 min previos de la ventana
        fueraDeVentana = viaje(empresaA, 180, 330);   // sale en 3 h
        yaLlego = viaje(empresaA, -300, -120);        // llegó hace 2 h
        cancelado = viaje(empresaA, -5, 145);
        evento("ItineraryCancelled", objectMapper.createObjectNode().put("itineraryId", cancelado.toString()));
        deOtraEmpresa = viaje(empresaB, -15, 135);

        iniciar(enRuta);
        sesion(enRuta, "ACTIVO");
        sesion(enPausa, "EN_PAUSA");
        sesion(sinSenal, "SIN_SENAL");
        sesion(deOtraEmpresa, "ACTIVO");
        asignar(enRuta, conductorAna, "PRINCIPAL");
        asignar(enRuta, UUID.randomUUID(), "RELEVO");
        alerta(enRuta, empresaA, "AVERIA", "ALTA", "ABIERTA");
        alerta(enRuta, empresaA, "SOS", "CRITICA", "ABIERTA");
        alerta(enRuta, empresaA, "ACCIDENTE", "CRITICA", "RESUELTA");
        alerta(enPausa, empresaA, "VIA_CERRADA", "ALTA", "ABIERTA");

        // En la terminal (llega a Bogotá) y casi sin avanzar a los 20 min: va retrasado.
        posicion(enRuta, BOGOTA[0], BOGOTA[1], 2);
        posicion(enRuta, BOGOTA[0] + 0.005 * (CHOCONTA[0] - BOGOTA[0]),
                BOGOTA[1] + 0.005 * (CHOCONTA[1] - BOGOTA[1]), 20);

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                mvc.perform(get("/fleet/live").with(operador(empresaA)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath(viajeConId(enRuta) + ".posicion.lat").isNotEmpty()));

        mvc.perform(get("/fleet/live").with(operador(empresaA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generadoEn").isNotEmpty())
                .andExpect(jsonPath("$.viajes.length()").value(4))
                .andExpect(jsonPath("$.viajes[*].itineraryId").value(hasItems(enRuta.toString(), enPausa.toString(),
                        sinSenal.toString(), porSalir.toString())))
                .andExpect(jsonPath("$.viajes[*].itineraryId").value(not(hasItem(fueraDeVentana.toString()))))
                .andExpect(jsonPath("$.viajes[*].itineraryId").value(not(hasItem(yaLlego.toString()))))
                .andExpect(jsonPath("$.viajes[*].itineraryId").value(not(hasItem(cancelado.toString()))))
                .andExpect(jsonPath("$.viajes[*].itineraryId").value(not(hasItem(deOtraEmpresa.toString()))))
                // Resumen: 1 en ruta (iniciado), 1 por estado, 1 retrasado, 2 con alerta abierta.
                .andExpect(jsonPath("$.resumen.total").value(4))
                .andExpect(jsonPath("$.resumen.enRuta").value(1))
                .andExpect(jsonPath("$.resumen.activos").value(1))
                .andExpect(jsonPath("$.resumen.enPausa").value(1))
                .andExpect(jsonPath("$.resumen.sinSenal").value(1))
                .andExpect(jsonPath("$.resumen.retrasados").value(1))
                .andExpect(jsonPath("$.resumen.conAlerta").value(2))
                // El viaje en ruta, completo.
                .andExpect(jsonPath(viajeConId(enRuta) + ".busId").value(busDe(enRuta).toString()))
                .andExpect(jsonPath(viajeConId(enRuta) + ".origen").value("Bogotá"))
                .andExpect(jsonPath(viajeConId(enRuta) + ".destino").value("Tunja"))
                .andExpect(jsonPath(viajeConId(enRuta) + ".iniciado").value(true))
                .andExpect(jsonPath(viajeConId(enRuta) + ".finalizado").value(false))
                .andExpect(jsonPath(viajeConId(enRuta) + ".estadoRastreo").value("ACTIVO"))
                .andExpect(jsonPath(viajeConId(enRuta) + ".posicion.velocidad").value(35.5))
                .andExpect(jsonPath(viajeConId(enRuta) + ".posicion.rumbo").value(45.0))
                .andExpect(jsonPath(viajeConId(enRuta) + ".posicion.timestamp").isNotEmpty())
                .andExpect(jsonPath(viajeConId(enRuta) + ".siguienteParada.orden").value(2))
                .andExpect(jsonPath(viajeConId(enRuta) + ".siguienteParada.nombre").value("Chocontá"))
                .andExpect(jsonPath(viajeConId(enRuta) + ".siguienteParada.estimada").isNotEmpty())
                .andExpect(jsonPath(viajeConId(enRuta) + ".ultimaParadaAlcanzada.nombre").value("Bogotá"))
                .andExpect(jsonPath(viajeConId(enRuta) + ".alertas.abiertas").value(2))
                .andExpect(jsonPath(viajeConId(enRuta) + ".alertas.mayorGravedad").value("CRITICA"))
                .andExpect(jsonPath(viajeConId(enRuta) + ".conductor.conductorId").value(conductorAna.toString()))
                .andExpect(jsonPath(viajeConId(enRuta) + ".conductor.nombre").value("Ana Conductora"))
                // Los demás: sin posición, ni ETA, ni conductor.
                .andExpect(jsonPath(viajeConId(enPausa) + ".estadoRastreo").value("EN_PAUSA"))
                .andExpect(jsonPath(viajeConId(enPausa) + ".alertas.mayorGravedad").value("ALTA"))
                .andExpect(jsonPath(viajeConId(sinSenal) + ".estadoRastreo").value("SIN_SENAL"))
                .andExpect(jsonPath(viajeConId(porSalir) + ".alertas.abiertas").value(0));

        JsonNode viajes = objectMapper.readTree(mvc.perform(get("/fleet/live").with(operador(empresaA)))
                .andReturn().getResponse().getContentAsString()).path("viajes");
        assertThat(viaje(viajes, enRuta).path("retrasoMinutos").asLong()).isGreaterThan(10);
        JsonNode sinSalir = viaje(viajes, porSalir);
        for (String campo : List.of("estadoRastreo", "posicion", "retrasoMinutos", "siguienteParada", "conductor")) {
            assertThat(sinSalir.path(campo).isNull()).as(campo).isTrue();
        }
    }

    private static JsonNode viaje(JsonNode viajes, UUID id) {
        for (JsonNode v : viajes) {
            if (id.toString().equals(v.path("itineraryId").asText())) {
                return v;
            }
        }
        throw new AssertionError("No está el viaje " + id);
    }

    @Test
    @Order(2)
    void unOperadorSoloVeSuEmpresaYElAdminLaQuePidaOTodas() throws Exception {
        // El companyId de la consulta no le sirve al operador para ver otra empresa.
        mvc.perform(get("/fleet/live").param("companyId", empresaB.toString()).with(operador(empresaA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viajes.length()").value(4))
                .andExpect(jsonPath("$.viajes[*].itineraryId").value(not(hasItem(deOtraEmpresa.toString()))));
        mvc.perform(get("/fleet/live").with(operador(empresaB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viajes.length()").value(1))
                .andExpect(jsonPath("$.viajes[0].itineraryId").value(deOtraEmpresa.toString()))
                .andExpect(jsonPath("$.resumen.activos").value(1));

        mvc.perform(get("/fleet/live").param("companyId", empresaB.toString()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viajes.length()").value(1))
                .andExpect(jsonPath("$.viajes[0].itineraryId").value(deOtraEmpresa.toString()));
        mvc.perform(get("/fleet/live").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viajes.length()").value(5))
                .andExpect(jsonPath("$.viajes[*].itineraryId").value(hasItems(enRuta.toString(),
                        deOtraEmpresa.toString())))
                .andExpect(jsonPath("$.viajes[*].itineraryId").value(not(hasItem(cancelado.toString()))));

        mvc.perform(get("/fleet/live")).andExpect(status().isUnauthorized());
        mvc.perform(get("/fleet/live").with(conductor())).andExpect(status().isForbidden());
    }

    @Test
    @Order(3)
    void positionUpdatedYEtaUpdatedLlevanLaEmpresa() throws Exception {
        JsonNode posicion = ultimoPayload("PositionUpdated", enRuta);
        assertThat(posicion.fieldNames()).toIterable().containsExactly("busId", "itineraryId", "companyId", "lat",
                "lng", "velocidad", "rumbo", "timestamp");
        assertThat(posicion.path("companyId").asText()).isEqualTo(empresaA.toString());
        assertThat(posicion.toString()).doesNotContain("conductor").doesNotContain(conductorAna.toString());

        JsonNode eta = ultimoPayload("EtaUpdated", enRuta);
        assertThat(eta.fieldNames()).toIterable().containsExactly("itineraryId", "companyId", "retrasoMinutos",
                "paradas");
        assertThat(eta.path("companyId").asText()).isEqualTo(empresaA.toString());

        // También llegan así al stream de eventos.
        relay.relay();
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(redis.opsForStream().read(StreamOffset.fromStart("tripbus:events:test")))
                        .filteredOn(r -> List.of("PositionUpdated", "EtaUpdated").contains(r.getValue().get("type")))
                        .filteredOn(r -> String.valueOf(r.getValue().get("payload")).contains(enRuta.toString()))
                        .hasSizeGreaterThanOrEqualTo(2)
                        .allMatch(r -> String.valueOf(r.getValue().get("payload")).contains(empresaA.toString())));
    }

    /** Al final: tras una falla, el mapa deja de llamar a identity por 30 s. */
    @Test
    @Order(4)
    void conIdentityCaidoElMapaCargaSinNombre() throws Exception {
        UUID empresaC = UUID.randomUUID();
        UUID viaje = viaje(empresaC, -10, 140);
        UUID conductorSinIdentity = UUID.randomUUID();
        identity.stubFor(WireMock.get(urlEqualTo("/internal/users/" + conductorSinIdentity))
                .willReturn(aResponse().withStatus(500)));
        asignar(viaje, conductorSinIdentity, "PRINCIPAL");
        sesion(viaje, "ACTIVO");

        mvc.perform(get("/fleet/live").with(operador(empresaC)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viajes.length()").value(1))
                .andExpect(jsonPath("$.viajes[0].conductor.conductorId").value(conductorSinIdentity.toString()))
                .andExpect(jsonPath("$.viajes[0].conductor.nombre").doesNotExist())
                .andExpect(jsonPath("$.resumen.activos").value(1));

        // Ana sigue en caché: su nombre no depende de identity.
        mvc.perform(get("/fleet/live").with(operador(empresaA)))
                .andExpect(jsonPath(viajeConId(enRuta) + ".conductor.nombre").value("Ana Conductora"));
    }
}
