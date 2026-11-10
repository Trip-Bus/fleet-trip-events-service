package com.tripbus.fleet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripbus.fleet.client.CatalogClient;
import com.tripbus.fleet.common.events.EventEnvelope;
import com.tripbus.fleet.events.CatalogSyncHandler;
import com.tripbus.fleet.events.FleetEventsHandler;
import com.tripbus.fleet.service.OutboxRelay;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HT-16 (sincronización con catalog), US-171 (iniciar y finalizar el viaje) y US-176 (novedades graves
 * y SOS) contra PostgreSQL y Redis reales (Testcontainers). Las asignaciones (US-121) se insertan directo.
 */
@SpringBootTest(properties = {"tripbus.jobs.tracking-status-ms=3600000", "TELEMETRY_STREAM=tripbus:telemetry:test"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
class TripOperationIT {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final String CHECKLIST_COMPLETO = """
            {"checklist":{"manifiestoRevisado":true,"documentosBus":true,"lucesYLlantas":true,
            "botiquinYExtintor":true},"observacion":"Todo en orden"}""";

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

    private final UUID companyId = UUID.randomUUID();

    @BeforeAll
    void preparar() {
        flyway.clean();
        flyway.migrate();
        redis.delete(List.of("tripbus:telemetry:test", "tripbus:events:test"));
    }

    // ---------- utilidades ----------

    private record Viaje(UUID itineraryId, UUID busId, UUID routeId) {
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

    private Viaje publicar(UUID routeId, Instant salida, Instant llegada) {
        Viaje viaje = new Viaje(UUID.randomUUID(), UUID.randomUUID(), routeId);
        evento("ItineraryPublished", objectMapper.createObjectNode()
                .put("itineraryId", viaje.itineraryId().toString()).put("busId", viaje.busId().toString())
                .put("routeId", routeId.toString()).put("companyId", companyId.toString())
                .put("origen", "Bogotá").put("destino", "Medellín")
                .put("fechaSalida", salida.toString()).put("fechaLlegada", llegada.toString()));
        return viaje;
    }

    /** Sale en 20 min (ya dentro de la ventana, antes de la hora de salida) y llega en 6 h. */
    private Viaje enVentana() {
        Instant salida = Instant.now().plus(20, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        return publicar(UUID.randomUUID(), salida, salida.plus(6, ChronoUnit.HOURS));
    }

    private Viaje dentroDe(long dias, long horas) {
        Instant salida = Instant.now().plus(dias, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        return publicar(UUID.randomUUID(), salida, salida.plus(horas, ChronoUnit.HOURS));
    }

    private void cancelar(Viaje viaje) {
        evento("ItineraryCancelled", objectMapper.createObjectNode().put("itineraryId", viaje.itineraryId().toString()));
    }

    private UUID asignar(Viaje viaje) {
        UUID conductor = UUID.randomUUID();
        asignar(viaje, conductor);
        return conductor;
    }

    private void asignar(Viaje viaje, UUID conductor) {
        jdbc.update("insert into trip_assignments (id, itinerary_id, conductor_id, rol, asignado_por, asignado_en) "
                + "values (?, ?, ?, 'PRINCIPAL', ?, now())", UUID.randomUUID(), viaje.itineraryId(), conductor,
                UUID.randomUUID());
    }

    private RequestPostProcessor conductor(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()).claim("role", "CONDUCTOR").claim("companyId", companyId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_CONDUCTOR"));
    }

    private RequestPostProcessor operador(UUID company) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "OPERADOR")
                        .claim("companyId", company.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_OPERADOR"));
    }

    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private ResultActions iniciar(UUID conductor, Viaje viaje, String body) throws Exception {
        return mvc.perform(post("/driver/trips/{id}/start", viaje.itineraryId()).with(conductor(conductor))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions novedad(RequestPostProcessor usuario, Viaje viaje, String body) throws Exception {
        return mvc.perform(post("/trips/{id}/events", viaje.itineraryId()).with(usuario)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions sos(UUID conductor, String body) throws Exception {
        return mvc.perform(post("/driver/sos").with(conductor(conductor)).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private List<String> outbox(String tipo, UUID id) {
        return jdbc.queryForList("select payload from outbox where event_type = ? and payload like ? order by creado_en",
                String.class, tipo, "%" + id + "%");
    }

    private long novedades(Viaje viaje, String tipo) {
        return jdbc.queryForObject("select count(*) from trip_events where itinerary_id = ? and tipo = ?", Long.class,
                viaje.itineraryId(), tipo);
    }

    private JsonNode json(String texto) throws Exception {
        return objectMapper.readTree(texto);
    }

    // ---------- HT-16: sincronización con catalog ----------

    private ObjectNode ruta(UUID routeId, String... nombres) {
        ObjectNode payload = objectMapper.createObjectNode().put("routeId", routeId.toString());
        var paradas = payload.putArray("paradas");
        for (int i = 0; i < nombres.length; i++) {
            paradas.addObject().put("orden", i + 1).put("nombre", nombres[i]).put("lat", 4.6 + i).put("lng", -74.1 - i)
                    .put("minutosDesdeSalida", i * 60);
        }
        return payload;
    }

    @Test
    void lasRutasDeCatalogReemplazanLasParadas() throws Exception {
        UUID routeId = UUID.randomUUID();
        evento("RouteCreated", ruta(routeId, "Bogotá", "Medellín"));
        assertThat(jdbc.queryForList("select nombre from route_stops where route_id = ? order by orden", String.class,
                routeId)).containsExactly("Bogotá", "Medellín");

        evento("RouteUpdated", ruta(routeId, "Bogotá", "Honda", "Medellín"));
        evento("RouteUpdated", ruta(routeId, "Bogotá", "Honda", "Medellín")); // repetido: mismo resultado
        assertThat(jdbc.queryForList("select nombre from route_stops where route_id = ? order by orden", String.class,
                routeId)).containsExactly("Bogotá", "Honda", "Medellín");

        // El mapa usa las paradas sincronizadas sin volver a pedirlas a catalog.
        Viaje viaje = publicar(routeId, Instant.now().plus(1, ChronoUnit.DAYS), Instant.now().plus(30, ChronoUnit.HOURS));
        mvc.perform(get("/itineraries/{id}/bus-position", viaje.itineraryId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paradas.length()").value(3))
                .andExpect(jsonPath("$.paradas[1].nombre").value("Honda"));
        verify(catalogClient, never()).stops(any());
    }

    @Test
    void reprogramarMueveLaVentanaYQuitaAlConductorQueQuedaCruzado() throws Exception {
        UUID conductor = UUID.randomUUID();
        Viaje primero = dentroDe(2, 4);
        Viaje segundo = dentroDe(4, 4);
        asignar(primero, conductor);
        asignar(segundo, conductor);
        UUID otroConductor = UUID.randomUUID();
        Viaje sinCruce = dentroDe(6, 4);
        asignar(sinCruce, otroConductor);

        // El segundo pasa a salir una hora después del primero: se cruza.
        Instant salidaNueva = Instant.now().plus(2, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS)
                .truncatedTo(ChronoUnit.SECONDS);
        evento("ItineraryRescheduled", objectMapper.createObjectNode().put("itineraryId", segundo.itineraryId().toString())
                .put("fechaSalida", salidaNueva.toString())
                .put("fechaLlegada", salidaNueva.plus(5, ChronoUnit.HOURS).toString()));
        evento("ItineraryRescheduled", objectMapper.createObjectNode().put("itineraryId", sinCruce.itineraryId().toString())
                .put("fechaSalida", Instant.now().plus(7, ChronoUnit.DAYS).toString())
                .put("fechaLlegada", Instant.now().plus(7, ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS).toString()));

        assertThat(jdbc.queryForObject("select fecha_salida from fleet_itineraries where itinerary_id = ?",
                java.sql.Timestamp.class, segundo.itineraryId()).toInstant()).isEqualTo(salidaNueva);
        assertThat(jdbc.queryForObject("select count(*) from trip_assignments where itinerary_id = ?", Long.class,
                segundo.itineraryId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from trip_assignments where itinerary_id = ?", Long.class,
                primero.itineraryId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from trip_assignments where itinerary_id = ?", Long.class,
                sinCruce.itineraryId())).isEqualTo(1L);

        List<String> conflictos = outbox("DriverAssignmentConflict", segundo.itineraryId());
        assertThat(conflictos).hasSize(1);
        JsonNode conflicto = json(conflictos.get(0));
        assertThat(conflicto.path("conductorId").asText()).isEqualTo(conductor.toString());
        assertThat(conflicto.path("conflictoCon").asText()).isEqualTo(primero.itineraryId().toString());
        assertThat(conflicto.path("companyId").asText()).isEqualTo(companyId.toString());
        assertThat(outbox("DriverUnassigned", segundo.itineraryId())).hasSize(1);
        assertThat(outbox("DriverAssignmentConflict", sinCruce.itineraryId())).isEmpty();

        // Un itinerario que fleet no conoce se ignora.
        evento("ItineraryRescheduled", objectMapper.createObjectNode().put("itineraryId", UUID.randomUUID().toString())
                .put("fechaSalida", salidaNueva.toString()).put("fechaLlegada", salidaNueva.toString()));
    }

    @Test
    void cambiarElBusMantieneLaSesionYLaAsignacion() throws Exception {
        Viaje viaje = enVentana();
        UUID conductor = asignar(viaje);
        mvc.perform(post("/driver/tracking/start").with(conductor(conductor))).andExpect(status().isOk());
        UUID busNuevo = UUID.randomUUID();

        evento("ItineraryBusChanged", objectMapper.createObjectNode().put("itineraryId", viaje.itineraryId().toString())
                .put("busNuevo", busNuevo.toString()));

        mvc.perform(get("/itineraries/{id}/bus-position", viaje.itineraryId()))
                .andExpect(jsonPath("$.busId").value(busNuevo.toString()))
                .andExpect(jsonPath("$.estadoRastreo").value("ACTIVO"));
        assertThat(jdbc.queryForObject("select count(*) from trip_assignments where itinerary_id = ?", Long.class,
                viaje.itineraryId())).isEqualTo(1L);
    }

    // ---------- US-171: iniciar y finalizar el viaje ----------

    @Test
    void elConductorIniciaElViajeConElChecklistCompletoUnaSolaVez() throws Exception {
        Viaje viaje = enVentana();
        UUID conductor = asignar(viaje);

        iniciar(conductor, viaje, """
                {"checklist":{"manifiestoRevisado":true,"documentosBus":false,"lucesYLlantas":true}}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CHECKLIST_INCOMPLETE"))
                .andExpect(jsonPath("$.error.details.faltantes.length()").value(2))
                .andExpect(jsonPath("$.error.details.faltantes").value(hasItem("documentosBus")))
                .andExpect(jsonPath("$.error.details.faltantes").value(hasItem("botiquinYExtintor")));
        iniciar(conductor, viaje, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        iniciar(conductor, viaje, CHECKLIST_COMPLETO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itineraryId").value(viaje.itineraryId().toString()))
                .andExpect(jsonPath("$.iniciadoEn").isNotEmpty())
                .andExpect(jsonPath("$.estadoRastreo").value("ACTIVO"));
        iniciar(conductor, viaje, CHECKLIST_COMPLETO)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRIP_ALREADY_STARTED"));

        assertThat(novedades(viaje, "INICIO")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select items from trip_checklists where itinerary_id = ?", String.class,
                viaje.itineraryId())).contains("botiquinYExtintor");
        assertThat(jdbc.queryForObject("select iniciado_por from fleet_itineraries where itinerary_id = ?", UUID.class,
                viaje.itineraryId())).isEqualTo(conductor);
        // EN_RUTA tras el inicio aunque todavía no llegue la hora de salida.
        mvc.perform(get("/itineraries/{id}/bus-position", viaje.itineraryId()))
                .andExpect(jsonPath("$.estado").value("EN_RUTA"))
                .andExpect(jsonPath("$.estadoRastreo").value("ACTIVO"));
        String fecha = LocalDate.ofInstant(Instant.now().plus(20, ChronoUnit.MINUTES), BOGOTA).toString();
        mvc.perform(get("/driver/trips").param("fecha", fecha).with(conductor(conductor)))
                .andExpect(jsonPath("$[0].iniciado").value(true))
                .andExpect(jsonPath("$[0].finalizado").value(false));
    }

    @Test
    void elInicioNoDuplicaElInicioDelSimulador() throws Exception {
        Viaje viaje = enVentana();
        UUID conductor = asignar(viaje);
        jdbc.update("insert into trip_events (id, itinerary_id, tipo, descripcion, origen, ocurrido_en) "
                + "values (?, ?, 'INICIO', 'El bus salió', 'SIMULADOR', now())", UUID.randomUUID(), viaje.itineraryId());

        iniciar(conductor, viaje, CHECKLIST_COMPLETO).andExpect(status().isOk());

        assertThat(novedades(viaje, "INICIO")).isEqualTo(1L);
    }

    @Test
    void soloSeIniciaUnViajePropioDentroDeSuVentana() throws Exception {
        Viaje lejano = dentroDe(2, 4);
        UUID conductor = asignar(lejano);
        iniciar(conductor, lejano, CHECKLIST_COMPLETO).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OUTSIDE_TRACKING_WINDOW"));

        Viaje ajeno = enVentana();
        iniciar(conductor, ajeno, CHECKLIST_COMPLETO).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ASSIGNED"));

        Viaje cancelado = enVentana();
        UUID otro = asignar(cancelado);
        cancelar(cancelado);
        asignar(cancelado, otro); // cancelar quita las asignaciones; se vuelve a asignar para probar la regla
        iniciar(otro, cancelado, CHECKLIST_COMPLETO).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRIP_CANCELLED"));

        mvc.perform(post("/driver/trips/{id}/start", UUID.randomUUID()).with(conductor(conductor))
                        .contentType(MediaType.APPLICATION_JSON).content(CHECKLIST_COMPLETO))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TRIP_NOT_FOUND"));
    }

    @Test
    void elConductorFinalizaElViajeSoloSiYaInicio() throws Exception {
        Viaje viaje = enVentana();
        UUID conductor = asignar(viaje);

        mvc.perform(post("/driver/trips/{id}/finish", viaje.itineraryId()).with(conductor(conductor)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRIP_NOT_STARTED"));
        iniciar(conductor, viaje, CHECKLIST_COMPLETO).andExpect(status().isOk());

        mvc.perform(post("/driver/trips/{id}/finish", viaje.itineraryId()).with(conductor(conductor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalizadoEn").isNotEmpty())
                .andExpect(jsonPath("$.estadoRastreo").value("FINALIZADO"));
        mvc.perform(post("/driver/trips/{id}/finish", viaje.itineraryId()).with(conductor(conductor)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRIP_ALREADY_FINISHED"));

        assertThat(novedades(viaje, "FIN")).isEqualTo(1L);
        // FINALIZADO aunque falten horas para la llegada estimada; y ya no acepta posiciones.
        mvc.perform(get("/itineraries/{id}/bus-position", viaje.itineraryId()))
                .andExpect(jsonPath("$.estado").value("FINALIZADO"))
                .andExpect(jsonPath("$.estadoRastreo").value("FINALIZADO"));
        mvc.perform(post("/driver/location").with(conductor(conductor)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\":4.6,\"lng\":-74.1,\"precision\":10}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRACKING_NOT_ACTIVE"));
        assertThat(outbox("TrackingStatusChanged", viaje.itineraryId())).hasSize(2); // ACTIVO y FINALIZADO
    }

    @Test
    void elOperadorDeLaEmpresaOUnAdminPuedenForzarElFin() throws Exception {
        Viaje viaje = enVentana();
        UUID conductor = asignar(viaje);
        iniciar(conductor, viaje, CHECKLIST_COMPLETO).andExpect(status().isOk());

        mvc.perform(post("/trips/{id}/finish", viaje.itineraryId()).with(conductor(conductor)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/trips/{id}/finish", viaje.itineraryId()).with(operador(UUID.randomUUID())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TRIP_NOT_FOUND"));
        mvc.perform(post("/trips/{id}/finish", viaje.itineraryId()).with(operador(companyId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estadoRastreo").value("FINALIZADO"));
        assertThat(novedades(viaje, "FIN")).isEqualTo(1L);

        // Un ADMIN también; un viaje que nunca inició no se puede finalizar.
        Viaje otro = enVentana();
        mvc.perform(post("/trips/{id}/finish", otro.itineraryId()).with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRIP_NOT_STARTED"));
        UUID otroConductor = asignar(otro);
        iniciar(otroConductor, otro, CHECKLIST_COMPLETO).andExpect(status().isOk());
        mvc.perform(post("/trips/{id}/finish", otro.itineraryId()).with(admin())).andExpect(status().isOk());
    }

    // ---------- US-176: novedades graves y SOS ----------

    @Test
    void lasNovedadesGravesCreanUnaAlertaYSiguenEnLaLineaDeTiempo() throws Exception {
        Viaje viaje = enVentana();
        UUID conductor = asignar(viaje);

        novedad(conductor(conductor), viaje, """
                {"tipo":"AVERIA","descripcion":"Pinchazo","lat":5.1,"lng":-74.6,"puedeContinuar":true}""")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.tipo").value("AVERIA"));
        // Sin coordenadas: se usa la última posición del viaje.
        jdbc.update("insert into bus_positions (event_id, bus_id, itinerary_id, lat, lng, registrado_en, recibido_en) "
                + "values (?, ?, ?, 5.5, -74.9, now(), now())", UUID.randomUUID().toString(), viaje.busId(),
                viaje.itineraryId());
        novedad(conductor(conductor), viaje, """
                {"tipo":"ACCIDENTE","descripcion":"Choque leve","puedeContinuar":false}""")
                .andExpect(status().isCreated());
        novedad(conductor(conductor), viaje, "{\"tipo\":\"RETRASO\",\"descripcion\":\"Trancón\"}")
                .andExpect(status().isCreated());

        mvc.perform(get("/trips/{id}/timeline", viaje.itineraryId()))
                .andExpect(jsonPath("$[*].tipo").value(hasItem("AVERIA")))
                .andExpect(jsonPath("$[*].tipo").value(hasItem("ACCIDENTE")));

        mvc.perform(get("/trips/{id}/alerts", viaje.itineraryId()).with(operador(companyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)) // RETRASO no crea alerta
                .andExpect(jsonPath("$[0].tipo").value("ACCIDENTE"))
                .andExpect(jsonPath("$[0].gravedad").value("CRITICA"))
                .andExpect(jsonPath("$[0].lat").value(5.5))
                .andExpect(jsonPath("$[0].puedeContinuar").value(false))
                .andExpect(jsonPath("$[1].gravedad").value("ALTA"))
                .andExpect(jsonPath("$[1].lat").value(5.1));

        List<String> raised = outbox("TripAlertRaised", viaje.itineraryId());
        assertThat(raised).hasSize(2);
        assertThat(json(raised.get(0)).fieldNames()).toIterable().containsExactly("alertId", "itineraryId", "companyId",
                "busId", "tipo", "gravedad", "lat", "lng", "mensaje", "puedeContinuar");
        // Ni la alerta ni la línea de tiempo pública llevan datos del conductor.
        assertThat(raised).allSatisfy(p -> assertThat(p).doesNotContain(conductor.toString()));
        assertThat(outbox("TripEventReported", viaje.itineraryId()))
                .allSatisfy(p -> assertThat(p).doesNotContain(conductor.toString()));
        List<String> interrumpido = outbox("TripInterrupted", viaje.itineraryId());
        assertThat(interrumpido).hasSize(1);
        assertThat(json(interrumpido.get(0)).path("tipo").asText()).isEqualTo("ACCIDENTE");

        relay.relay();
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(redis.opsForStream().read(StreamOffset.fromStart("tripbus:events:test")))
                        .anyMatch(r -> "TripAlertRaised".equals(r.getValue().get("type"))));
    }

    @Test
    void elSosRepetidoDevuelveLaMismaAlertaYNoVaALaLineaDeTiempo() throws Exception {
        Viaje viaje = enVentana();
        UUID conductor = asignar(viaje);

        String primera = sos(conductor, "{\"lat\":5.0,\"lng\":-74.5,\"mensaje\":\"Ayuda\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("SOS"))
                .andExpect(jsonPath("$.gravedad").value("CRITICA"))
                .andExpect(jsonPath("$.estado").value("ABIERTA"))
                .andReturn().getResponse().getContentAsString();
        String alertId = json(primera).path("alertId").asText();

        sos(conductor, "{}").andExpect(status().isOk()).andExpect(jsonPath("$.alertId").value(alertId));
        mvc.perform(post("/driver/sos").with(conductor(conductor))).andExpect(status().isOk())
                .andExpect(jsonPath("$.alertId").value(alertId));
        // También por POST /trips/{id}/events con tipo SOS: es el mismo.
        novedad(conductor(conductor), viaje, "{\"tipo\":\"SOS\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.alertId").value(alertId));

        assertThat(jdbc.queryForObject("select count(*) from trip_alerts where itinerary_id = ? and tipo = 'SOS'",
                Long.class, viaje.itineraryId())).isEqualTo(1L);
        assertThat(outbox("TripAlertRaised", viaje.itineraryId())).hasSize(1);
        assertThat(novedades(viaje, "SOS")).isZero();
        assertThat(outbox("TripEventReported", viaje.itineraryId())).isEmpty();
        mvc.perform(get("/trips/{id}/timeline", viaje.itineraryId()))
                .andExpect(jsonPath("$[*].tipo").value(not(hasItem("SOS"))));

        // Resuelto, un nuevo SOS crea otra alerta.
        mvc.perform(post("/trips/{id}/alerts/{alertId}/resolve", viaje.itineraryId(), alertId).with(operador(companyId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nota\":\"Policía en camino\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("RESUELTA"));
        sos(conductor, "{}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.alertId").value(not(alertId)));
    }

    @Test
    void sinViajeActivoNoHaySos() throws Exception {
        sos(UUID.randomUUID(), "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_TRIP"));
        mvc.perform(post("/driver/sos").with(operador(companyId))).andExpect(status().isForbidden());
    }

    @Test
    void elOperadorConsultaYResuelveSoloLasAlertasDeSuEmpresa() throws Exception {
        Viaje viaje = enVentana();
        UUID conductor = asignar(viaje);
        String alerta = novedad(conductor(conductor), viaje, "{\"tipo\":\"VIA_CERRADA\",\"lat\":5,\"lng\":-74}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(alerta).contains("VIA_CERRADA");
        String alertId = jdbc.queryForObject("select id from trip_alerts where itinerary_id = ?", String.class,
                viaje.itineraryId());

        UUID otraEmpresa = UUID.randomUUID();
        mvc.perform(post("/trips/{id}/alerts/{alertId}/resolve", viaje.itineraryId(), alertId).with(operador(otraEmpresa)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ALERT_NOT_FOUND"));
        mvc.perform(get("/trips/{id}/alerts", viaje.itineraryId()).with(operador(otraEmpresa)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/trips/{id}/alerts/{alertId}/resolve", UUID.randomUUID(), alertId).with(operador(companyId)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/trips/{id}/alerts/{alertId}/resolve", viaje.itineraryId(), alertId).with(conductor(conductor)))
                .andExpect(status().isForbidden());

        mvc.perform(get("/alerts").param("estado", "ABIERTA").with(operador(companyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].alertId").value(hasItem(alertId)));
        mvc.perform(get("/alerts").with(operador(otraEmpresa)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(post("/trips/{id}/alerts/{alertId}/resolve", viaje.itineraryId(), alertId).with(operador(companyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RESUELTA"))
                .andExpect(jsonPath("$.resueltoEn").isNotEmpty());
        mvc.perform(post("/trips/{id}/alerts/{alertId}/resolve", viaje.itineraryId(), alertId).with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ALERT_ALREADY_RESOLVED"));
        assertThat(outbox("TripAlertResolved", viaje.itineraryId())).hasSize(1);

        mvc.perform(get("/alerts").param("estado", "ABIERTA").with(operador(companyId)))
                .andExpect(jsonPath("$[*].alertId").value(not(hasItem(alertId))));
        mvc.perform(get("/alerts").param("estado", "RESUELTA").with(admin()))
                .andExpect(jsonPath("$[*].alertId").value(hasItem(alertId)));
        mvc.perform(get("/alerts").with(admin()))
                .andExpect(jsonPath("$[*].alertId").value(hasItem(alertId)));
        mvc.perform(get("/alerts").with(operador(companyId)))
                .andExpect(jsonPath("$[*].alertId").value(hasItem(alertId)));
        // Operador sin empresa en el token: ninguna.
        mvc.perform(get("/alerts").with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "OPERADOR"))
                        .authorities(new SimpleGrantedAuthority("ROLE_OPERADOR"))))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
