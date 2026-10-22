package com.tripbus.fleet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.tripbus.fleet.client.CatalogClient;
import com.tripbus.fleet.client.IdentityClient;
import com.tripbus.fleet.common.error.ApiException;
import com.tripbus.fleet.common.events.EventEnvelope;
import com.tripbus.fleet.domain.DriverRole;
import com.tripbus.fleet.events.FleetEventsHandler;
import com.tripbus.fleet.service.DriverAssignmentService;
import com.tripbus.fleet.service.OutboxRelay;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US-121 (asignación de conductores) y US-170 (mis viajes de hoy) contra PostgreSQL y Redis reales
 * (Testcontainers). Identity se simula por HTTP con WireMock (GET /internal/users/{id}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
class DriverIT {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final String INTERNAL_TOKEN = "tripbus_internal_qa_token";

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final WireMockServer identity = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    // Los contenedores se arrancan aquí (sin @Container) para que sigan vivos mientras @DirtiesContext
    // cierra el contexto al terminar la clase: si se detuvieran antes, el relay programado del outbox
    // se quedaría esperando una conexión y el cierre de la JVM tardaría 30 s. Ryuk los borra al final.
    static {
        identity.start();
        postgres.start();
        redisContainer.start();
    }

    @DynamicPropertySource
    static void identityUrl(DynamicPropertyRegistry registry) {
        registry.add("tripbus.services.identity-url", identity::baseUrl);
        registry.add("TELEMETRY_STREAM", () -> "tripbus:telemetry:test");
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private FleetEventsHandler handler;
    @Autowired
    private DriverAssignmentService assignmentService;
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

    private final UUID companyId = UUID.randomUUID();

    @BeforeAll
    void preparar() {
        flyway.clean();
        flyway.migrate();
        redis.delete(List.of("tripbus:telemetry:test", "tripbus:events:test"));
        // La primera petición a WireMock (en frío) puede pasar de los 800 ms de lectura del cliente:
        // se calienta antes de las pruebas para que ninguna dé 503 por eso.
        UUID calentar = conductor();
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
                .until(() -> identityClient.user(calentar).isPresent());
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
                .put("origen", "Bogotá").put("destino", "Medellín")
                .put("fechaSalida", salida.toString())
                .put("fechaLlegada", llegada.toString());
        tx.executeWithoutResult(s -> handler.handle(
                new EventEnvelope(UUID.randomUUID().toString(), "ItineraryPublished", "test", Instant.now(), payload)));
        return itinerary;
    }

    /** Un viaje futuro de 6 h que sale dentro de {@code horas}. */
    private UUID viajeEn(long horas) {
        Instant salida = Instant.now().plus(horas, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        return publicar(salida, salida.plus(6, ChronoUnit.HOURS));
    }

    private void cancelar(UUID itinerary) {
        var payload = objectMapper.createObjectNode().put("itineraryId", itinerary.toString());
        tx.executeWithoutResult(s -> handler.handle(
                new EventEnvelope(UUID.randomUUID().toString(), "ItineraryCancelled", "test", Instant.now(), payload)));
    }

    /** Registra en identity (WireMock) un usuario con ese rol, empresa y estado. */
    private UUID usuario(String rol, UUID company, boolean activo) {
        UUID id = UUID.randomUUID();
        identity.stubFor(WireMock.get(urlEqualTo("/internal/users/" + id))
                .withHeader("X-Internal-Token", equalTo(INTERNAL_TOKEN))
                .willReturn(okJson("""
                        {"id":"%s","nombre":"Usuario","rol":"%s","companyId":%s,"activo":%s}
                        """.formatted(id, rol, company == null ? "null" : "\"" + company + "\"", activo))));
        return id;
    }

    private UUID conductor() {
        return usuario("CONDUCTOR", companyId, true);
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

    private RequestPostProcessor comoConductor(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString()).claim("role", "CONDUCTOR")
                        .claim("companyId", companyId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_CONDUCTOR"));
    }

    private ResultActions asignar(RequestPostProcessor usuario, UUID viaje, UUID conductor, String rol)
            throws Exception {
        String rolJson = rol == null ? "" : ",\"rol\":\"%s\"".formatted(rol);
        return mvc.perform(put("/trips/{id}/driver", viaje).with(usuario).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conductorId\":\"%s\"%s}".formatted(conductor, rolJson)));
    }

    private long eventos(String tipo, UUID conductor) {
        return jdbc.queryForObject("select count(*) from outbox where event_type = ? and payload like ?",
                Long.class, tipo, "%" + conductor + "%");
    }

    private long asignaciones(UUID viaje) {
        return jdbc.queryForObject("select count(*) from trip_assignments where itinerary_id = ?", Long.class, viaje);
    }

    // ---------- US-121 ----------

    @Test
    void elOperadorAsignaReemplazaConsultaYQuitaConductores() throws Exception {
        UUID viaje = viajeEn(2);
        UUID principal = conductor();
        UUID relevo = conductor();

        asignar(operador(companyId), viaje, principal, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itineraryId").value(viaje.toString()))
                .andExpect(jsonPath("$.conductorId").value(principal.toString()))
                .andExpect(jsonPath("$.rol").value("PRINCIPAL"))
                .andExpect(jsonPath("$.asignadoPor").isNotEmpty());
        // Repetir la misma asignación no cambia nada ni publica otro evento.
        asignar(operador(companyId), viaje, principal, "PRINCIPAL").andExpect(status().isOk());
        assertThat(eventos("DriverAssigned", principal)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select payload from outbox where event_type = 'DriverAssigned' and payload like ?",
                String.class, "%" + principal + "%"))
                .contains(viaje.toString(), companyId.toString(), "\"rol\":\"PRINCIPAL\"");

        asignar(admin(), viaje, relevo, "RELEVO").andExpect(status().isOk());

        mvc.perform(get("/trips/{id}/driver", viaje).with(operador(companyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].rol").value("PRINCIPAL"))
                .andExpect(jsonPath("$[1].conductorId").value(relevo.toString()));
        mvc.perform(get("/trips/{id}/driver", viaje).with(comoConductor(principal)))
                .andExpect(status().isOk());
        mvc.perform(get("/trips/{id}/driver", viaje).with(admin()))
                .andExpect(status().isOk());
        mvc.perform(get("/trips/{id}/driver", viaje).with(comoConductor(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ASSIGNED"));
        mvc.perform(get("/trips/{id}/driver", viaje).with(operador(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRIP_NOT_FOUND"));

        // Reemplazar al principal: sale uno (DriverUnassigned) y entra el otro (DriverAssigned).
        UUID nuevo = conductor();
        asignar(operador(companyId), viaje, nuevo, "PRINCIPAL")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conductorId").value(nuevo.toString()));
        assertThat(eventos("DriverUnassigned", principal)).isEqualTo(1L);
        assertThat(eventos("DriverAssigned", nuevo)).isEqualTo(1L);
        assertThat(asignaciones(viaje)).isEqualTo(2L);

        // Quitar el relevo.
        mvc.perform(delete("/trips/{id}/driver/{rol}", viaje, "RELEVO").with(operador(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRIP_NOT_FOUND"));
        mvc.perform(delete("/trips/{id}/driver/{rol}", viaje, "RELEVO").with(operador(companyId)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/trips/{id}/driver/{rol}", viaje, "RELEVO").with(operador(companyId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ASSIGNMENT_NOT_FOUND"));
        mvc.perform(delete("/trips/{id}/driver/{rol}", viaje, "COPILOTO").with(operador(companyId)))
                .andExpect(status().isBadRequest());
        assertThat(eventos("DriverUnassigned", relevo)).isEqualTo(1L);
        assertThat(asignaciones(viaje)).isEqualTo(1L);

        // Un conductor no asigna ni quita.
        asignar(comoConductor(nuevo), viaje, nuevo, "RELEVO").andExpect(status().isForbidden());
        mvc.perform(delete("/trips/{id}/driver/{rol}", viaje, "PRINCIPAL").with(comoConductor(nuevo)))
                .andExpect(status().isForbidden());

        // El outbox llega al bus de eventos (el relay programado también puede tomarlas: se espera).
        relay.relay();
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(redis.opsForStream().read(StreamOffset.fromStart("tripbus:events:test")))
                        .anyMatch(r -> "DriverAssigned".equals(r.getValue().get("type")))
                        .anyMatch(r -> "DriverUnassigned".equals(r.getValue().get("type"))));
    }

    @Test
    void validaElViajeYElConductorEnIdentity() throws Exception {
        UUID viaje = viajeEn(2);

        asignar(operador(companyId), UUID.randomUUID(), conductor(), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TRIP_NOT_FOUND"));
        asignar(operador(UUID.randomUUID()), viaje, conductor(), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("TRIP_NOT_FOUND"));

        UUID cancelado = viajeEn(2);
        cancelar(cancelado);
        asignar(operador(companyId), cancelado, conductor(), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRIP_CANCELLED"));
        UUID terminado = publicar(Instant.now().minus(8, ChronoUnit.HOURS), Instant.now().minus(1, ChronoUnit.HOURS));
        asignar(operador(companyId), terminado, conductor(), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRIP_FINISHED"));

        asignar(operador(companyId), viaje, usuario("PASAJERO", null, true), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_DRIVER"));
        asignar(operador(companyId), viaje, usuario("OPERADOR", companyId, true), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_DRIVER"));
        UUID inexistente = UUID.randomUUID();
        identity.stubFor(WireMock.get(urlEqualTo("/internal/users/" + inexistente)).willReturn(aResponse().withStatus(404)));
        asignar(operador(companyId), viaje, inexistente, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_DRIVER"));
        asignar(operador(companyId), viaje, usuario("CONDUCTOR", companyId, false), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("DRIVER_INACTIVE"));
        asignar(operador(companyId), viaje, usuario("CONDUCTOR", UUID.randomUUID(), true), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("DRIVER_OTHER_COMPANY"));

        // Identity caído o lento (más de 800 ms de lectura) → 503.
        UUID caido = UUID.randomUUID();
        identity.stubFor(WireMock.get(urlEqualTo("/internal/users/" + caido)).willReturn(aResponse().withStatus(500)));
        asignar(operador(companyId), viaje, caido, null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("IDENTITY_UNAVAILABLE"));
        UUID lento = UUID.randomUUID();
        identity.stubFor(WireMock.get(urlEqualTo("/internal/users/" + lento))
                .willReturn(okJson("{}").withFixedDelay(2000)));
        asignar(operador(companyId), viaje, lento, null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("IDENTITY_UNAVAILABLE"));

        // Cuerpo inválido.
        mvc.perform(put("/trips/{id}/driver", viaje).with(operador(companyId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        asignar(operador(companyId), viaje, conductor(), "COPILOTO").andExpect(status().isBadRequest());

        assertThat(asignaciones(viaje)).isZero();
    }

    @Test
    void unConductorNoPuedeTenerViajesCruzados() throws Exception {
        UUID conductor = conductor();
        Instant salida = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        UUID primero = publicar(salida, salida.plus(4, ChronoUnit.HOURS));
        // Sale 45 min después de que llega el primero: las ventanas (±30 min) se cruzan.
        UUID cruzado = publicar(salida.plus(285, ChronoUnit.MINUTES), salida.plus(10, ChronoUnit.HOURS));
        // Sale 2 h después: no se cruza.
        UUID despues = publicar(salida.plus(6, ChronoUnit.HOURS), salida.plus(10, ChronoUnit.HOURS));

        asignar(operador(companyId), primero, conductor, null).andExpect(status().isOk());
        asignar(operador(companyId), cruzado, conductor, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DRIVER_SCHEDULE_CONFLICT"))
                .andExpect(jsonPath("$.error.details.itineraryId").value(primero.toString()));
        // Tampoco puede ser principal y relevo del mismo viaje.
        asignar(operador(companyId), primero, conductor, "RELEVO")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.itineraryId").value(primero.toString()));
        asignar(operador(companyId), despues, conductor, "RELEVO").andExpect(status().isOk());

        // Si el primero se cancela, deja de chocar.
        cancelar(primero);
        asignar(operador(companyId), cruzado, conductor, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.itineraryId").value(despues.toString()));
    }

    @Test
    void veinteAsignacionesSimultaneasDelMismoConductorDejanSoloUna() throws Exception {
        UUID conductor = conductor();
        Instant salida = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        List<UUID> viajes = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            viajes.add(publicar(salida.plus(i, ChronoUnit.MINUTES), salida.plus(5, ChronoUnit.HOURS)));
        }

        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<String>> resultados = new ArrayList<>();
        try {
            for (UUID viaje : viajes) {
                resultados.add(pool.submit(() -> {
                    largada.await();
                    try {
                        assignmentService.assign(viaje, conductor, DriverRole.PRINCIPAL, UUID.randomUUID(), true, null);
                        return "OK";
                    } catch (ApiException e) {
                        return e.getCode();
                    }
                }));
            }
            largada.countDown();
            List<String> codigos = new ArrayList<>();
            for (Future<String> r : resultados) {
                codigos.add(r.get());
            }
            assertThat(codigos).filteredOn("OK"::equals).hasSize(1);
            assertThat(codigos).filteredOn("DRIVER_SCHEDULE_CONFLICT"::equals).hasSize(19);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from trip_assignments where conductor_id = ?",
                Long.class, conductor)).isEqualTo(1L);
        assertThat(eventos("DriverAssigned", conductor)).isEqualTo(1L);
    }

    @Test
    void alCancelarElViajeSeQuitanSusConductores() throws Exception {
        UUID viaje = viajeEn(5);
        UUID principal = conductor();
        UUID relevo = conductor();
        asignar(operador(companyId), viaje, principal, "PRINCIPAL").andExpect(status().isOk());
        asignar(operador(companyId), viaje, relevo, "RELEVO").andExpect(status().isOk());

        cancelar(viaje);

        assertThat(asignaciones(viaje)).isZero();
        assertThat(eventos("DriverUnassigned", principal)).isEqualTo(1L);
        assertThat(eventos("DriverUnassigned", relevo)).isEqualTo(1L);
        mvc.perform(get("/trips/{id}/driver", viaje).with(operador(companyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---------- US-170 ----------

    @Test
    void elConductorVeSusViajesDelDiaOrdenadosPorSalida() throws Exception {
        UUID conductor = conductor();
        LocalDate dia = LocalDate.now(BOGOTA).plusDays(5);
        Instant tarde = dia.atTime(LocalTime.of(14, 0)).atZone(BOGOTA).toInstant();
        Instant manana = dia.atTime(LocalTime.of(6, 0)).atZone(BOGOTA).toInstant();
        UUID viajeTarde = publicar(tarde, tarde.plus(4, ChronoUnit.HOURS));
        UUID viajeManana = publicar(manana, manana.plus(4, ChronoUnit.HOURS));
        UUID otroDia = publicar(manana.plus(1, ChronoUnit.DAYS), manana.plus(28, ChronoUnit.HOURS));
        asignar(operador(companyId), viajeTarde, conductor, "RELEVO").andExpect(status().isOk());
        asignar(operador(companyId), viajeManana, conductor, null).andExpect(status().isOk());
        asignar(operador(companyId), otroDia, conductor, null).andExpect(status().isOk());

        mvc.perform(get("/driver/trips").param("fecha", dia.toString()).with(comoConductor(conductor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].itineraryId").value(viajeManana.toString()))
                .andExpect(jsonPath("$[0].rol").value("PRINCIPAL"))
                .andExpect(jsonPath("$[0].estado").value("PROGRAMADO"))
                .andExpect(jsonPath("$[0].origen").value("Bogotá"))
                .andExpect(jsonPath("$[0].busId").isNotEmpty())
                .andExpect(jsonPath("$[0].fechaSalida").value(manana.toString()))
                .andExpect(jsonPath("$[0].ventanaInicio").value(manana.minus(30, ChronoUnit.MINUTES).toString()))
                .andExpect(jsonPath("$[0].ventanaFin").value(manana.plus(270, ChronoUnit.MINUTES).toString()))
                .andExpect(jsonPath("$[0].puedeCompartirUbicacion").value(false))
                .andExpect(jsonPath("$[1].itineraryId").value(viajeTarde.toString()))
                .andExpect(jsonPath("$[1].rol").value("RELEVO"));

        mvc.perform(get("/driver/trips").param("fecha", "22-10-2026").with(comoConductor(conductor)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/driver/trips").with(operador(companyId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void elConductorVeElViajeQueTieneAhoraEnSuVentana() throws Exception {
        UUID conductor = conductor();
        mvc.perform(get("/driver/trips/active").with(comoConductor(conductor)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_TRIP"));

        // Sale en 10 min: ya está dentro de la ventana (30 min antes de salir).
        Instant salida = Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        UUID viaje = publicar(salida, salida.plus(4, ChronoUnit.HOURS));
        asignar(operador(companyId), viaje, conductor, null).andExpect(status().isOk());

        mvc.perform(get("/driver/trips/active").with(comoConductor(conductor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itineraryId").value(viaje.toString()))
                .andExpect(jsonPath("$.estado").value("PROGRAMADO"))
                .andExpect(jsonPath("$.puedeCompartirUbicacion").value(true));

        // Sin fecha es hoy en America/Bogota: mismo resultado que pedir la fecha de hoy.
        String hoy = mvc.perform(get("/driver/trips").param("fecha", LocalDate.now(BOGOTA).toString())
                        .with(comoConductor(conductor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/driver/trips").with(comoConductor(conductor)))
                .andExpect(status().isOk())
                .andExpect(content().json(hoy));
        mvc.perform(get("/driver/trips").param("fecha", LocalDate.ofInstant(salida, BOGOTA).toString())
                        .with(comoConductor(conductor)))
                .andExpect(jsonPath("$[0].itineraryId").value(viaje.toString()))
                .andExpect(jsonPath("$[0].puedeCompartirUbicacion").value(true));
    }

    // ---------- Novedades ----------

    @Test
    void elConductorSoloReportaNovedadesDeViajesDondeEstaAsignado() throws Exception {
        UUID viaje = viajeEn(1);
        UUID asignado = conductor();
        UUID otro = conductor();
        asignar(operador(companyId), viaje, asignado, null).andExpect(status().isOk());
        String novedad = "{\"tipo\":\"RETRASO\",\"descripcion\":\"Trancón\"}";

        mvc.perform(post("/trips/{id}/events", viaje).with(comoConductor(otro))
                        .contentType(MediaType.APPLICATION_JSON).content(novedad))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ASSIGNED"));
        mvc.perform(post("/trips/{id}/events", viaje).with(comoConductor(asignado))
                        .contentType(MediaType.APPLICATION_JSON).content(novedad))
                .andExpect(status().isCreated());
        // El operador de la empresa sigue igual que antes: no necesita estar asignado.
        mvc.perform(post("/trips/{id}/events", viaje).with(operador(companyId))
                        .contentType(MediaType.APPLICATION_JSON).content(novedad))
                .andExpect(status().isCreated());
    }
}
