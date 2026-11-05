package com.tripbus.fleet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.tripbus.fleet.client.CatalogClient;
import com.tripbus.fleet.common.events.EventEnvelope;
import com.tripbus.fleet.events.FleetEventsHandler;
import com.tripbus.fleet.service.DriverTrackingService;
import com.tripbus.fleet.service.EtaStore;
import com.tripbus.fleet.service.PositionRetentionJob;
import com.tripbus.fleet.web.dto.EtaView;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US-181 (compartir el viaje), US-182 (puntualidad y recorrido), US-193 (reporte de puntualidad) y HT-08
 * (retención de posiciones) contra PostgreSQL y Redis reales (Testcontainers). Identity (nombres de los
 * conductores) se simula con WireMock y las paradas de catalog con un mock.
 */
@SpringBootTest(properties = {"tripbus.jobs.tracking-status-ms=3600000", "TELEMETRY_STREAM=tripbus:telemetry:test",
        "tripbus.fleet.positions.batch-size=2"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
class HistorialViajeIT {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final String CHECKLIST = """
            {"checklist":{"manifiestoRevisado":true,"documentosBus":true,"lucesYLlantas":true,
            "botiquinYExtintor":true}}""";

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
    private DriverTrackingService trackingService;
    @Autowired
    private PositionRetentionJob retentionJob;
    @Autowired
    private EtaStore etaStore;
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
    private final UUID otraEmpresa = UUID.randomUUID();

    @BeforeAll
    void preparar() {
        flyway.clean();
        flyway.migrate();
    }

    @AfterAll
    void detenerIdentity() {
        identity.stop();
    }

    // ---------- utilidades ----------

    private record Viaje(UUID itineraryId, UUID routeId, Instant salida, Instant llegada) {
    }

    private Viaje publicar(UUID company, UUID routeId, Instant salida, Instant llegada) {
        Viaje viaje = new Viaje(UUID.randomUUID(), routeId, salida, llegada);
        var payload = objectMapper.createObjectNode()
                .put("itineraryId", viaje.itineraryId().toString()).put("busId", UUID.randomUUID().toString())
                .put("routeId", routeId.toString()).put("companyId", company.toString())
                .put("origen", "Bogotá").put("destino", "Tunja")
                .put("fechaSalida", salida.toString()).put("fechaLlegada", llegada.toString());
        tx.executeWithoutResult(s -> fleetHandler.handle(
                new EventEnvelope(UUID.randomUUID().toString(), "ItineraryPublished", "test", Instant.now(), payload)));
        return viaje;
    }

    /** Sale en salidaDesdeAhora y dura duracion. */
    private Viaje viaje(Duration salidaDesdeAhora, Duration duracion) {
        Instant salida = Instant.now().plus(salidaDesdeAhora).truncatedTo(ChronoUnit.SECONDS);
        return publicar(companyId, UUID.randomUUID(), salida, salida.plus(duracion));
    }

    private void cancelar(Viaje viaje) {
        var payload = objectMapper.createObjectNode().put("itineraryId", viaje.itineraryId().toString());
        tx.executeWithoutResult(s -> fleetHandler.handle(
                new EventEnvelope(UUID.randomUUID().toString(), "ItineraryCancelled", "test", Instant.now(), payload)));
    }

    private void asignar(Viaje viaje, UUID conductor) {
        jdbc.update("insert into trip_assignments (id, itinerary_id, conductor_id, rol, asignado_por, asignado_en) "
                + "values (?, ?, ?, 'PRINCIPAL', ?, now())", UUID.randomUUID(), viaje.itineraryId(), conductor,
                UUID.randomUUID());
    }

    private void novedad(Viaje viaje, String tipo, String detalle, Instant cuando) {
        jdbc.update("insert into trip_events (id, itinerary_id, tipo, descripcion, payload, origen, ocurrido_en) "
                + "values (?, ?, ?, ?, ?, 'AUTOMATICO', ?)", UUID.randomUUID(), viaje.itineraryId(), tipo, tipo,
                detalle, Timestamp.from(cuando));
    }

    private void posicion(UUID itineraryId, UUID busId, Instant cuando) {
        jdbc.update("insert into bus_positions (event_id, bus_id, itinerary_id, lat, lng, registrado_en, recibido_en) "
                        + "values (?, ?, ?, 4.6, -74.1, ?, ?)", UUID.randomUUID().toString(), busId, itineraryId,
                Timestamp.from(cuando), Timestamp.from(cuando));
    }

    private long posiciones(UUID itineraryId) {
        return jdbc.queryForObject("select count(*) from bus_positions where itinerary_id = ?", Long.class,
                itineraryId);
    }

    private RequestPostProcessor usuario(UUID id, String rol, UUID company) {
        return jwt().jwt(j -> {
            j.subject(id.toString()).claim("role", rol);
            if (company != null) {
                j.claim("companyId", company.toString());
            }
        }).authorities(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    private RequestPostProcessor pasajero(UUID id) {
        return usuario(id, "PASAJERO", null);
    }

    private ResultActions compartir(UUID userId, Viaje viaje) throws Exception {
        return mvc.perform(post("/share-links").with(pasajero(userId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itineraryId\":\"%s\"}".formatted(viaje.itineraryId())));
    }

    private String codigo(ResultActions respuesta) throws Exception {
        return objectMapper.readTree(respuesta.andReturn().getResponse().getContentAsString()).path("codigo").asText();
    }

    private JsonNode json(ResultActions respuesta) throws Exception {
        return objectMapper.readTree(respuesta.andReturn().getResponse().getContentAsString());
    }

    // ---------- US-181: compartir mi viaje ----------

    @Test
    void elEnlaceCreadoMuestraElViajeSinDatosDelPasajero() throws Exception {
        UUID pasajero = UUID.randomUUID();
        Viaje viaje = viaje(Duration.ofHours(1), Duration.ofHours(3));

        ResultActions creado = compartir(pasajero, viaje).andExpect(status().isCreated())
                .andExpect(jsonPath("$.itineraryId").value(viaje.itineraryId().toString()))
                .andExpect(jsonPath("$.venceEn").value(viaje.llegada().plus(2, ChronoUnit.HOURS).toString()));
        String codigo = codigo(creado);
        assertThat(codigo).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}");
        // En Redis solo va el itinerario y el vencimiento.
        assertThat(redis.opsForValue().get("fleet:share:" + codigo)).doesNotContain(pasajero.toString());

        mvc.perform(get("/share/{codigo}", codigo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itineraryId").value(viaje.itineraryId().toString()))
                .andExpect(jsonPath("$.origen").value("Bogotá"))
                .andExpect(jsonPath("$.destino").value("Tunja"))
                .andExpect(jsonPath("$.fechaSalida").value(viaje.salida().toString()))
                .andExpect(jsonPath("$.venceEn").exists())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(pasajero.toString()))));
    }

    @Test
    void elEnlaceVencidoDa404() throws Exception {
        // Llegó hace 2 h menos 3 s: el enlace vive 3 s.
        Instant llegada = Instant.now().minus(2, ChronoUnit.HOURS).plusSeconds(3);
        Viaje viaje = publicar(companyId, UUID.randomUUID(), llegada.minus(4, ChronoUnit.HOURS), llegada);
        String codigo = codigo(compartir(UUID.randomUUID(), viaje).andExpect(status().isCreated()));
        mvc.perform(get("/share/{codigo}", codigo)).andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                mvc.perform(get("/share/{codigo}", codigo))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value("SHARE_LINK_NOT_FOUND")));
    }

    @Test
    void masDeCincoEnlacesPorUsuarioYViajeDa409() throws Exception {
        UUID pasajero = UUID.randomUUID();
        Viaje viaje = viaje(Duration.ofHours(2), Duration.ofHours(3));
        for (int i = 0; i < 5; i++) {
            compartir(pasajero, viaje).andExpect(status().isCreated());
        }

        compartir(pasajero, viaje).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHARE_LINK_LIMIT"));
        // Otro usuario (u otro viaje) tiene su propio límite.
        compartir(UUID.randomUUID(), viaje).andExpect(status().isCreated());
        compartir(pasajero, viaje(Duration.ofHours(2), Duration.ofHours(3))).andExpect(status().isCreated());
    }

    @Test
    void enlacesInvalidosYViajesQueNoSePuedenCompartir() throws Exception {
        mvc.perform(get("/share/{codigo}", "ABCDEFGH")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SHARE_LINK_NOT_FOUND"));
        mvc.perform(get("/share/{codigo}", "abc0O1IL")).andExpect(status().isNotFound());

        Viaje cancelado = viaje(Duration.ofHours(2), Duration.ofHours(3));
        cancelar(cancelado);
        compartir(UUID.randomUUID(), cancelado).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRIP_CANCELLED"));
        Viaje viejo = viaje(Duration.ofDays(-2), Duration.ofHours(3));
        compartir(UUID.randomUUID(), viejo).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRIP_FINISHED"));
        mvc.perform(post("/share-links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itineraryId\":\"%s\"}".formatted(viejo.itineraryId())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/share-links").with(pasajero(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itineraryId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TRIP_NOT_FOUND"));
    }

    @Test
    void elEnlaceVenceConLaLlegadaEstimadaSiHayEta() throws Exception {
        Viaje viaje = viaje(Duration.ofHours(-1), Duration.ofHours(3));
        Instant estimada = viaje.llegada().plus(40, ChronoUnit.MINUTES);
        etaStore.save(new EtaView(viaje.itineraryId(), 40, Instant.now(), List.of(
                new EtaView.Parada(1, "Bogotá", viaje.salida(), null, true),
                new EtaView.Parada(2, "Tunja", viaje.llegada(), estimada, false))));

        compartir(UUID.randomUUID(), viaje).andExpect(status().isCreated())
                .andExpect(jsonPath("$.venceEn").value(estimada.plus(2, ChronoUnit.HOURS).toString()));
    }

    // ---------- US-182: puntualidad y recorrido ----------

    private void paradas(UUID routeId) {
        when(catalogClient.stops(routeId)).thenReturn(List.of(
                new CatalogClient.Stop(1, "Bogotá", 4.65, -74.10, 0),
                new CatalogClient.Stop(2, "Chocontá", 5.15, -73.68, 60),
                new CatalogClient.Stop(3, "Tunja", 5.53, -73.36, 130)));
    }

    @Test
    void laPuntualidadSeCalculaAlFinalizarYElRecorridoLaMuestra() throws Exception {
        UUID conductor = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        paradas(routeId);
        Instant salida = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Viaje viaje = publicar(companyId, routeId, salida, salida.plus(130, ChronoUnit.MINUTES));
        asignar(viaje, conductor);
        // Salió 3 min tarde (a tiempo en Bogotá) y llegó a Chocontá 8 min tarde (no a tiempo).
        novedad(viaje, "INICIO", null, salida.plus(3, ChronoUnit.MINUTES));
        novedad(viaje, "LLEGADA_PARADA", "{\"orden\":2,\"parada\":\"Chocontá\"}", salida.plus(68, ChronoUnit.MINUTES));
        UUID busId = UUID.randomUUID();
        Instant minuto = salida.plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES);
        for (int s = 0; s < 60; s += 15) {
            posicion(viaje.itineraryId(), busId, minuto.plusSeconds(s));
        }
        posicion(viaje.itineraryId(), busId, minuto.plusSeconds(75));

        mvc.perform(get("/itineraries/{id}/track", viaje.itineraryId()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TRIP_NOT_FINISHED"));
        mvc.perform(post("/driver/trips/{id}/start", viaje.itineraryId()).with(usuario(conductor, "CONDUCTOR", companyId))
                .contentType(MediaType.APPLICATION_JSON).content(CHECKLIST)).andExpect(status().isOk());
        mvc.perform(post("/driver/trips/{id}/finish", viaje.itineraryId()).with(usuario(conductor, "CONDUCTOR", companyId)))
                .andExpect(status().isOk());

        var fila = jdbc.queryForMap("select * from trip_punctuality where itinerary_id = ?", viaje.itineraryId());
        assertThat(fila.get("minutos_retraso_salida")).isEqualTo(3);
        // Llegó (FIN, ahora) unos 10 min antes de lo programado.
        assertThat((Integer) fila.get("minutos_retraso_llegada")).isBetween(-10, -9);
        assertThat(fila.get("paradas_a_tiempo")).isEqualTo(2);
        assertThat(fila.get("paradas_total")).isEqualTo(3);
        assertThat(fila.get("conductor_id")).isEqualTo(conductor);
        assertThat(fila.get("route_id")).isEqualTo(routeId);
        assertThat(fila.get("company_id")).isEqualTo(companyId);

        mvc.perform(get("/itineraries/{id}/track", viaje.itineraryId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origen").value("Bogotá"))
                .andExpect(jsonPath("$.recorrido.length()").value(2))
                .andExpect(jsonPath("$.recorrido[0].timestamp").value(minuto.toString()))
                .andExpect(jsonPath("$.paradas.length()").value(3))
                .andExpect(jsonPath("$.paradas[0].real").value(salida.plus(3, ChronoUnit.MINUTES).toString()))
                .andExpect(jsonPath("$.paradas[0].aTiempo").value(true))
                .andExpect(jsonPath("$.paradas[1].aTiempo").value(false))
                .andExpect(jsonPath("$.paradas[2].aTiempo").value(true))
                .andExpect(jsonPath("$.puntualidad.minutosRetrasoSalida").value(3))
                .andExpect(jsonPath("$.puntualidad.paradasATiempo").value(2))
                .andExpect(jsonPath("$.puntualidad.paradasTotal").value(3))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(conductor.toString()))));

        // US-181: tras el fin, el enlace vive hasta 2 h después de la llegada real.
        JsonNode enlace = json(compartir(UUID.randomUUID(), viaje).andExpect(status().isCreated()));
        assertThat(Instant.parse(enlace.path("venceEn").asText())).isBefore(Instant.now().plus(2, ChronoUnit.HOURS));
    }

    @Test
    void laPuntualidadSeRegistraCuandoElRastreoPasaAFinalizado() throws Exception {
        Viaje viaje = viaje(Duration.ofHours(-4), Duration.ofHours(3));
        jdbc.update("insert into tracking_sessions (itinerary_id, estado, actualizado_en) values (?, 'ACTIVO', now())",
                viaje.itineraryId());

        trackingService.actualizarEstados();

        var fila = jdbc.queryForMap("select * from trip_punctuality where itinerary_id = ?", viaje.itineraryId());
        assertThat(fila.get("salida_real")).isNull();
        assertThat(fila.get("minutos_retraso_salida")).isNull();
        assertThat(fila.get("paradas_total")).isEqualTo(0);
        // Sin resumen guardado también se puede ver (se calcula al vuelo) si el viaje terminó por horario.
        Viaje sinRastreo = viaje(Duration.ofHours(-5), Duration.ofHours(3));
        mvc.perform(get("/itineraries/{id}/track", sinRastreo.itineraryId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorrido.length()").value(0))
                .andExpect(jsonPath("$.puntualidad.salidaReal").doesNotExist());
    }

    @Test
    void elRecorridoDeUnViajeNoTerminadoCanceladoOInexistenteDa404() throws Exception {
        Viaje enCurso = viaje(Duration.ofHours(-1), Duration.ofHours(3));
        mvc.perform(get("/itineraries/{id}/track", enCurso.itineraryId()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TRIP_NOT_FINISHED"));
        Viaje cancelado = viaje(Duration.ofDays(-3), Duration.ofHours(3));
        cancelar(cancelado);
        mvc.perform(get("/itineraries/{id}/track", cancelado.itineraryId()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TRIP_NOT_FINISHED"));
        mvc.perform(get("/itineraries/{id}/track", UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TRIP_NOT_FOUND"));
    }

    // ---------- US-193: reporte de puntualidad ----------

    private void puntualidad(UUID company, UUID routeId, UUID conductor, Instant salida, Integer retrasoSalida,
                             Integer retrasoLlegada, int paradasATiempo, int paradasTotal) {
        Viaje viaje = publicar(company, routeId, salida, salida.plus(3, ChronoUnit.HOURS));
        jdbc.update("""
                insert into trip_punctuality (itinerary_id, salida_programada, llegada_programada,
                    minutos_retraso_salida, minutos_retraso_llegada, paradas_a_tiempo, paradas_total, conductor_id,
                    route_id, company_id, calculado_en)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())""", viaje.itineraryId(), Timestamp.from(salida),
                Timestamp.from(viaje.llegada()), retrasoSalida, retrasoLlegada, paradasATiempo, paradasTotal, conductor,
                routeId, company);
    }

    private ResultActions reporte(RequestPostProcessor quien, String query) throws Exception {
        return mvc.perform(get("/reports/punctuality?" + query).with(quien));
    }

    @Test
    void reportePorRutaYPorConductorSoloDeSuEmpresa() throws Exception {
        UUID ruta1 = UUID.randomUUID();
        UUID ruta2 = UUID.randomUUID();
        UUID ana = UUID.randomUUID();
        UUID beto = UUID.randomUUID();
        identity.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/internal/users/" + ana))
                .willReturn(okJson("""
                        {"id":"%s","nombre":"Ana","rol":"CONDUCTOR","companyId":"%s","activo":true}"""
                        .formatted(ana, companyId))));
        identity.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/internal/users/" + beto))
                .willReturn(aResponse().withStatus(404)));
        Instant dia = LocalDate.of(2026, 3, 10).atTime(8, 0).atZone(BOGOTA).toInstant();
        puntualidad(companyId, ruta1, ana, dia, 2, 5, 3, 4);
        puntualidad(companyId, ruta1, ana, dia.plus(1, ChronoUnit.DAYS), 10, 20, 2, 4);
        puntualidad(companyId, ruta2, beto, dia.plus(2, ChronoUnit.DAYS), null, null, 0, 0);
        // Fuera del rango y de otra empresa: no cuentan.
        puntualidad(companyId, ruta1, ana, dia.plus(40, ChronoUnit.DAYS), 0, 0, 4, 4);
        puntualidad(otraEmpresa, ruta1, ana, dia, 30, 60, 0, 4);
        RequestPostProcessor operador = usuario(UUID.randomUUID(), "OPERADOR", companyId);

        // El companyId de otra empresa en la consulta se ignora para un operador.
        reporte(operador, "desde=2026-03-01&hasta=2026-03-31&agrupar=ruta&companyId=" + otraEmpresa)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.grupos.length()").value(2))
                .andExpect(jsonPath("$.grupos[0].routeId").value(ruta1.toString()))
                .andExpect(jsonPath("$.grupos[0].viajes").value(2))
                .andExpect(jsonPath("$.grupos[0].retrasoPromedioSalidaMin").value(6.0))
                .andExpect(jsonPath("$.grupos[0].retrasoPromedioLlegadaMin").value(12.5))
                .andExpect(jsonPath("$.grupos[0].porcentajeViajesATiempo").value(50.0))
                .andExpect(jsonPath("$.grupos[0].porcentajeParadasATiempo").value(62.5))
                .andExpect(jsonPath("$.grupos[1].routeId").value(ruta2.toString()))
                .andExpect(jsonPath("$.grupos[1].retrasoPromedioLlegadaMin").doesNotExist())
                .andExpect(jsonPath("$.grupos[1].porcentajeViajesATiempo").value(0.0))
                .andExpect(jsonPath("$.grupos[1].porcentajeParadasATiempo").doesNotExist());

        reporte(operador, "desde=2026-03-01&hasta=2026-03-31&agrupar=conductor")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grupos.length()").value(2))
                .andExpect(jsonPath("$.grupos[0].conductorId").value(ana.toString()))
                .andExpect(jsonPath("$.grupos[0].nombre").value("Ana"))
                .andExpect(jsonPath("$.grupos[0].routeId").doesNotExist())
                .andExpect(jsonPath("$.grupos[1].conductorId").value(beto.toString()))
                .andExpect(jsonPath("$.grupos[1].nombre").doesNotExist());

        // Otra empresa solo ve lo suyo.
        reporte(usuario(UUID.randomUUID(), "OPERADOR", otraEmpresa), "desde=2026-03-01&hasta=2026-03-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agrupar").value("ruta"))
                .andExpect(jsonPath("$.grupos.length()").value(1))
                .andExpect(jsonPath("$.grupos[0].retrasoPromedioLlegadaMin").value(60.0));
    }

    @Test
    void elAdminEligeLaEmpresaOVeTodas() throws Exception {
        UUID ruta = UUID.randomUUID();
        Instant dia = LocalDate.of(2025, 6, 5).atTime(9, 0).atZone(BOGOTA).toInstant();
        puntualidad(companyId, ruta, null, dia, 0, 0, 1, 1);
        puntualidad(otraEmpresa, ruta, null, dia, 0, 30, 0, 1);
        RequestPostProcessor admin = usuario(UUID.randomUUID(), "ADMIN", null);

        reporte(admin, "desde=2025-06-05&hasta=2025-06-05&companyId=" + otraEmpresa)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grupos[0].viajes").value(1))
                .andExpect(jsonPath("$.grupos[0].porcentajeViajesATiempo").value(0.0));
        reporte(admin, "desde=2025-06-05&hasta=2025-06-05&agrupar=conductor")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").doesNotExist())
                .andExpect(jsonPath("$.grupos[0].viajes").value(2))
                .andExpect(jsonPath("$.grupos[0].conductorId").doesNotExist())
                .andExpect(jsonPath("$.grupos[0].porcentajeViajesATiempo").value(50.0));
    }

    @Test
    void reporteConParametrosInvalidosOSinPermiso() throws Exception {
        RequestPostProcessor operador = usuario(UUID.randomUUID(), "OPERADOR", companyId);
        reporte(operador, "desde=2025-01-01&hasta=2026-01-02")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("RANGE_TOO_LARGE"));
        reporte(operador, "desde=2025-01-01&hasta=2026-01-01").andExpect(status().isOk());
        reporte(operador, "desde=2026-02-01&hasta=2026-01-01")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_RANGE"));
        reporte(operador, "desde=2026-01-01&hasta=2026-01-31&agrupar=bus")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_GROUPING"));
        reporte(operador, "desde=ayer&hasta=2026-01-31").andExpect(status().isBadRequest());
        reporte(usuario(UUID.randomUUID(), "OPERADOR", null), "desde=2026-01-01&hasta=2026-01-31")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("NO_COMPANY"));
        reporte(usuario(UUID.randomUUID(), "CONDUCTOR", companyId), "desde=2026-01-01&hasta=2026-01-31")
                .andExpect(status().isForbidden());
        mvc.perform(get("/reports/punctuality?desde=2026-01-01&hasta=2026-01-31")).andExpect(status().isUnauthorized());
    }

    // ---------- HT-08: retención de posiciones ----------

    @Test
    void lasPosicionesAntiguasSeResumenYLasMuyAntiguasSeBorran() throws Exception {
        redis.delete("fleet:positions:resumidas-hasta");
        Viaje viejo = viaje(Duration.ofDays(-8).minusHours(2), Duration.ofHours(3));
        Viaje reciente = viaje(Duration.ofDays(-2), Duration.ofHours(3));
        Viaje antiquisimo = viaje(Duration.ofDays(-91), Duration.ofHours(3));
        UUID bus = UUID.randomUUID();
        UUID busSinViaje = UUID.randomUUID();
        Instant minuto = viejo.salida().plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES);
        for (int s = 0; s < 60; s += 10) {
            posicion(viejo.itineraryId(), bus, minuto.plusSeconds(s));      // 6 en el mismo minuto → 1
        }
        posicion(viejo.itineraryId(), bus, minuto.plusSeconds(61));       // otro minuto → se queda
        posicion(viejo.itineraryId(), bus, minuto.plusSeconds(62));       // → se resume
        posicion(null, busSinViaje, minuto.plusSeconds(5));
        posicion(null, busSinViaje, minuto.plusSeconds(6));               // sin viaje: por bus → 1
        Instant minutoReciente = reciente.salida().plus(5, ChronoUnit.MINUTES);
        for (int s = 0; s < 3; s++) {
            posicion(reciente.itineraryId(), bus, minutoReciente.plusSeconds(s)); // menos de 7 días: intactas
        }
        posicion(antiquisimo.itineraryId(), bus, antiquisimo.salida().plusSeconds(10));
        posicion(antiquisimo.itineraryId(), bus, antiquisimo.salida().plusSeconds(20));

        PositionRetentionJob.Resultado resultado = retentionJob.ejecutar();

        assertThat(resultado.borradas()).isEqualTo(2);
        assertThat(resultado.resumidas()).isEqualTo(5 + 1 + 1);
        assertThat(posiciones(viejo.itineraryId())).isEqualTo(2);
        assertThat(posiciones(reciente.itineraryId())).isEqualTo(3);
        assertThat(posiciones(antiquisimo.itineraryId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from bus_positions where bus_id = ?", Long.class, busSinViaje))
                .isEqualTo(1);
        // Se queda la primera de cada minuto.
        assertThat(jdbc.queryForObject("select min(registrado_en) from bus_positions where itinerary_id = ?",
                Timestamp.class, viejo.itineraryId()).toInstant()).isEqualTo(minuto);

        // Otra corrida no encuentra nada más que hacer.
        assertThat(retentionJob.ejecutar()).isEqualTo(new PositionRetentionJob.Resultado(0, 0));

        // El recorrido sigue funcionando con las posiciones resumidas.
        mvc.perform(get("/itineraries/{id}/track", viejo.itineraryId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recorrido.length()").value(2))
                .andExpect(jsonPath("$.recorrido[0].timestamp").value(minuto.toString()));
    }
}
