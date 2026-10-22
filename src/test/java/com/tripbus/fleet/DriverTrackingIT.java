package com.tripbus.fleet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.fleet.client.CatalogClient;
import com.tripbus.fleet.common.events.EventEnvelope;
import com.tripbus.fleet.events.FleetEventsHandler;
import com.tripbus.fleet.service.DriverTrackingService;
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
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US-173 a US-175: el bus se rastrea con la ubicación del celular del conductor, contra PostgreSQL y
 * Redis reales (Testcontainers). El job de estados no corre solo (intervalo de 1 h): las pruebas lo
 * invocan, con signal-timeout de 2 s para ver el paso a SIN_SENAL.
 */
@SpringBootTest(properties = {
        "tripbus.fleet.signal-timeout=2s",
        "tripbus.jobs.tracking-status-ms=3600000",
        "TELEMETRY_STREAM=tripbus:telemetry:test"})
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
class DriverTrackingIT {

    private static final String STREAM = "tripbus:telemetry:test";

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
    private FleetEventsHandler handler;
    @Autowired
    private DriverTrackingService trackingService;
    @Autowired
    private TelemetryService telemetryService;
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
        redis.delete(List.of(STREAM, "tripbus:events:test"));
    }

    // ---------- utilidades ----------

    private record Viaje(UUID itineraryId, UUID busId) {
    }

    private Viaje publicar(Instant salida, Instant llegada) {
        Viaje viaje = new Viaje(UUID.randomUUID(), UUID.randomUUID());
        var payload = objectMapper.createObjectNode()
                .put("itineraryId", viaje.itineraryId().toString()).put("busId", viaje.busId().toString())
                .put("routeId", UUID.randomUUID().toString()).put("companyId", companyId.toString())
                .put("origen", "Bogotá").put("destino", "Medellín")
                .put("fechaSalida", salida.toString())
                .put("fechaLlegada", llegada.toString());
        tx.executeWithoutResult(s -> handler.handle(
                new EventEnvelope(UUID.randomUUID().toString(), "ItineraryPublished", "test", Instant.now(), payload)));
        return viaje;
    }

    /** Viaje en curso: salió hace 10 min y llega en 4 h (dentro de la ventana). */
    private Viaje enCurso() {
        Instant salida = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        return publicar(salida, salida.plus(4, ChronoUnit.HOURS));
    }

    private void cancelar(Viaje viaje) {
        var payload = objectMapper.createObjectNode().put("itineraryId", viaje.itineraryId().toString());
        tx.executeWithoutResult(s -> handler.handle(
                new EventEnvelope(UUID.randomUUID().toString(), "ItineraryCancelled", "test", Instant.now(), payload)));
    }

    /** La asignación ya la cubre DriverIT (US-121): aquí se inserta directo. */
    private void asignar(Viaje viaje, UUID conductor) {
        jdbc.update("insert into trip_assignments (id, itinerary_id, conductor_id, rol, asignado_por, asignado_en) "
                + "values (?, ?, ?, 'PRINCIPAL', ?, now())", UUID.randomUUID(), viaje.itineraryId(), conductor,
                UUID.randomUUID());
    }

    private UUID conductorEn(Viaje viaje) {
        UUID conductor = UUID.randomUUID();
        asignar(viaje, conductor);
        return conductor;
    }

    private RequestPostProcessor como(UUID conductor) {
        return jwt().jwt(j -> j.subject(conductor.toString()).claim("role", "CONDUCTOR")
                        .claim("companyId", companyId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_CONDUCTOR"));
    }

    private ResultActions start(UUID conductor) throws Exception {
        return mvc.perform(post("/driver/tracking/start").with(como(conductor)));
    }

    private ResultActions stop(UUID conductor) throws Exception {
        return mvc.perform(post("/driver/tracking/stop").with(como(conductor)));
    }

    private static String lectura(Instant ts, double lat, double lng, double precision) {
        return "{\"lat\":%s,\"lng\":%s,\"precision\":%s,\"velocidad\":60,\"rumbo\":90,\"timestamp\":\"%s\"}"
                .formatted(lat, lng, precision, ts);
    }

    private ResultActions enviar(UUID conductor, String json) throws Exception {
        return mvc.perform(post("/driver/location").with(como(conductor)).contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions lote(UUID conductor, String... lecturas) throws Exception {
        return mvc.perform(post("/driver/location/batch").with(como(conductor)).contentType(MediaType.APPLICATION_JSON)
                .content("[" + String.join(",", lecturas) + "]"));
    }

    private static Instant hace(long segundos) {
        return Instant.now().minusSeconds(segundos).truncatedTo(ChronoUnit.MILLIS);
    }

    private long encoladas(Viaje viaje) {
        return redis.opsForStream().read(StreamOffset.fromStart(STREAM)).stream()
                .filter(r -> viaje.busId().toString().equals(r.getValue().get("busId")))
                .count();
    }

    private void procesarEncoladas() {
        for (MapRecord<String, Object, Object> r : redis.opsForStream().read(StreamOffset.fromStart(STREAM))) {
            Map<String, String> fields = new HashMap<>();
            r.getValue().forEach((k, v) -> fields.put(k.toString(), v.toString()));
            telemetryService.process(fields);
        }
    }

    private String estadoSesion(Viaje viaje) {
        return jdbc.queryForObject("select estado from tracking_sessions where itinerary_id = ?", String.class,
                viaje.itineraryId());
    }

    private List<String> estadosPublicados(Viaje viaje) {
        return jdbc.queryForList("select payload from outbox where event_type = 'TrackingStatusChanged' "
                        + "and payload like ? order by creado_en", String.class, "%" + viaje.itineraryId() + "%")
                .stream().map(p -> {
                    try {
                        return objectMapper.readTree(p).path("estado").asText();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
    }

    // ---------- US-175: solo dentro de la ventana del viaje asignado ----------

    @Test
    void sinViajeAsignadoNoSeRastrea() throws Exception {
        UUID conductor = UUID.randomUUID();
        start(conductor).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_TRIP"));
        enviar(conductor, lectura(hace(1), 4.6, -74.1, 10)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_TRIP"));

        // Asignado a un viaje en ventana pero cancelado: tampoco.
        Viaje cancelado = enCurso();
        UUID otro = conductorEn(cancelado);
        cancelar(cancelado);
        start(otro).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("NO_ACTIVE_TRIP"));
    }

    @Test
    void fueraDeLaVentanaNoSeRastrea() throws Exception {
        Instant salida = Instant.now().plus(3, ChronoUnit.HOURS);
        Viaje viaje = publicar(salida, salida.plus(4, ChronoUnit.HOURS));
        UUID conductor = conductorEn(viaje);

        start(conductor).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OUTSIDE_TRACKING_WINDOW"));
        stop(conductor).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OUTSIDE_TRACKING_WINDOW"));
        lote(conductor, lectura(hace(1), 4.6, -74.1, 10)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OUTSIDE_TRACKING_WINDOW"));
        assertThat(encoladas(viaje)).isZero();
    }

    @Test
    void soloElConductorUsaEstosEndpoints() throws Exception {
        mvc.perform(post("/driver/location").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERADOR")))
                        .contentType(MediaType.APPLICATION_JSON).content(lectura(hace(1), 4.6, -74.1, 10)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/driver/tracking/start")).andExpect(status().isUnauthorized());
    }

    // ---------- US-173 / US-174: compartir, pausar y estado ----------

    @Test
    void elConductorCompartePausaYReanudaSuUbicacion() throws Exception {
        Viaje viaje = enCurso();
        UUID conductor = conductorEn(viaje);

        // Sin encender: no se guarda nada.
        enviar(conductor, lectura(hace(5), 4.60, -74.08, 12)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRACKING_NOT_ACTIVE"));
        assertThat(encoladas(viaje)).isZero();

        start(conductor).andExpect(status().isOk())
                .andExpect(jsonPath("$.itineraryId").value(viaje.itineraryId().toString()))
                .andExpect(jsonPath("$.busId").value(viaje.busId().toString()))
                .andExpect(jsonPath("$.estado").value("ACTIVO"));
        start(conductor).andExpect(status().isOk()); // repetir no publica otro cambio

        Instant ts = hace(5);
        enviar(conductor, lectura(ts, 4.60, -74.08, 12))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.aceptada").value(true))
                .andExpect(jsonPath("$.eventId").isNotEmpty())
                .andExpect(jsonPath("$.motivo").doesNotExist());
        assertThat(encoladas(viaje)).isEqualTo(1L);

        // El consumidor guarda conductor_id y precision, pero PositionUpdated no lleva conductorId.
        procesarEncoladas();
        Map<String, Object> fila = jdbc.queryForMap(
                "select conductor_id, precision, itinerary_id from bus_positions where bus_id = ?", viaje.busId());
        assertThat(fila.get("conductor_id")).isEqualTo(conductor);
        assertThat(fila.get("precision")).isEqualTo(12.0);
        assertThat(fila.get("itinerary_id")).isEqualTo(viaje.itineraryId());
        String evento = jdbc.queryForObject("select payload from outbox where event_type = 'PositionUpdated' "
                + "and payload like ?", String.class, "%" + viaje.busId() + "%");
        assertThat(evento).doesNotContain("conductor").doesNotContain(conductor.toString());

        mvc.perform(get("/itineraries/{id}/bus-position", viaje.itineraryId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_RUTA"))
                .andExpect(jsonPath("$.posicion.lat").value(4.60))
                .andExpect(jsonPath("$.estadoRastreo").value("ACTIVO"))
                .andExpect(jsonPath("$.ultimaPosicionEn").value(ts.toString()));

        // Pausa: no se acepta nada.
        stop(conductor).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("EN_PAUSA"));
        enviar(conductor, lectura(hace(1), 4.60, -74.08, 12)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRACKING_NOT_ACTIVE"));
        assertThat(encoladas(viaje)).isEqualTo(1L);
        mvc.perform(get("/itineraries/{id}/bus-position", viaje.itineraryId()))
                .andExpect(jsonPath("$.estadoRastreo").value("EN_PAUSA"));

        start(conductor).andExpect(jsonPath("$.estado").value("ACTIVO"));
        assertThat(estadosPublicados(viaje)).containsExactly("ACTIVO", "EN_PAUSA", "ACTIVO");
    }

    @Test
    void sinSesionElViajeNoTieneEstadoDeRastreo() throws Exception {
        Viaje viaje = enCurso();
        mvc.perform(get("/itineraries/{id}/bus-position", viaje.itineraryId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estadoRastreo").doesNotExist())
                .andExpect(jsonPath("$.ultimaPosicionEn").doesNotExist());
    }

    @Test
    void descartaLecturasMalasSinRomperElEnvio() throws Exception {
        Viaje viaje = enCurso();
        UUID conductor = conductorEn(viaje);
        start(conductor).andExpect(status().isOk());

        enviar(conductor, lectura(hace(30), 4.6000, -74.08, 150))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.aceptada").value(false))
                .andExpect(jsonPath("$.motivo").value("PRECISION_BAJA"));
        enviar(conductor, lectura(Instant.now().plus(5, ChronoUnit.MINUTES), 4.6, -74.08, 10))
                .andExpect(jsonPath("$.motivo").value("TIMESTAMP_FUTURO"));
        enviar(conductor, lectura(hace(15 * 60), 4.6, -74.08, 10))
                .andExpect(jsonPath("$.motivo").value("POSICION_ANTIGUA"));
        enviar(conductor, lectura(hace(30), 0, 0, 10))
                .andExpect(jsonPath("$.motivo").value("COORDENADAS_INVALIDAS"));

        enviar(conductor, lectura(hace(30), 4.6000, -74.08, 10)).andExpect(jsonPath("$.aceptada").value(true));
        // ~5,5 km en 10 s: salto imposible.
        enviar(conductor, lectura(hace(20), 4.6500, -74.08, 10))
                .andExpect(jsonPath("$.aceptada").value(false))
                .andExpect(jsonPath("$.motivo").value("SALTO_IMPOSIBLE"));
        // ~11 m en 20 s: normal.
        enviar(conductor, lectura(hace(10), 4.6001, -74.08, 10)).andExpect(jsonPath("$.aceptada").value(true));
        // Más vieja que la última guardada.
        enviar(conductor, lectura(hace(25), 4.6001, -74.08, 10))
                .andExpect(jsonPath("$.motivo").value("POSICION_DESORDENADA"));

        assertThat(encoladas(viaje)).isEqualTo(2L);
        // Validación del cuerpo: eso sí es un 400.
        enviar(conductor, "{\"lat\":95,\"lng\":-74,\"precision\":10}").andExpect(status().isBadRequest());
        enviar(conductor, "{\"lat\":4.6,\"lng\":-74}").andExpect(status().isBadRequest());
    }

    @Test
    void elLoteDesordenadoSeProcesaEnOrdenDeTimestamp() throws Exception {
        Viaje viaje = enCurso();
        UUID conductor = conductorEn(viaje);
        start(conductor).andExpect(status().isOk());

        Instant t1 = hace(240);
        Instant t2 = hace(180);
        Instant t3 = hace(120);
        Instant vieja = hace(20 * 60);
        lote(conductor,
                lectura(t3, 4.6020, -74.08, 10),
                lectura(t1, 4.6000, -74.08, 10),
                lectura(vieja, 4.5900, -74.08, 10),
                lectura(t2, 4.6010, -74.08, 10))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.aceptadas").value(3))
                .andExpect(jsonPath("$.descartadas").value(1))
                .andExpect(jsonPath("$.lecturas[0].timestamp").value(vieja.toString()))
                .andExpect(jsonPath("$.lecturas[0].motivo").value("POSICION_ANTIGUA"))
                .andExpect(jsonPath("$.lecturas[1].timestamp").value(t1.toString()))
                .andExpect(jsonPath("$.lecturas[2].timestamp").value(t2.toString()))
                .andExpect(jsonPath("$.lecturas[3].timestamp").value(t3.toString()))
                .andExpect(jsonPath("$.lecturas[3].aceptada").value(true));

        // Encoladas en orden de timestamp.
        List<String> encoladas = redis.opsForStream().read(StreamOffset.fromStart(STREAM)).stream()
                .filter(r -> viaje.busId().toString().equals(r.getValue().get("busId")))
                .map(r -> r.getValue().get("timestamp").toString()).toList();
        assertThat(encoladas).containsExactly(t1.toString(), t2.toString(), t3.toString());

        // Un segundo lote con una lectura anterior a la última guardada (t3): esa se descarta.
        lote(conductor, lectura(hace(150), 4.6015, -74.08, 10), lectura(hace(60), 4.6030, -74.08, 10))
                .andExpect(jsonPath("$.aceptadas").value(1))
                .andExpect(jsonPath("$.lecturas[0].motivo").value("POSICION_DESORDENADA"));

        // Límites del lote.
        lote(conductor).andExpect(status().isBadRequest());
        String[] muchas = IntStream.range(0, 51).mapToObj(i -> lectura(hace(i), 4.6, -74.08, 10))
                .toArray(String[]::new);
        lote(conductor, muchas).andExpect(status().isBadRequest());
        lote(conductor, "{\"lat\":4.6,\"lng\":-74}").andExpect(status().isBadRequest());
    }

    // ---------- US-174 / US-175: job de estados ----------

    @Test
    void sinPosicionesPasaASinSenalYUnaNuevaLoDevuelveAActivo() throws Exception {
        Viaje viaje = enCurso();
        UUID conductor = conductorEn(viaje);
        start(conductor).andExpect(status().isOk());
        enviar(conductor, lectura(hace(1), 4.6, -74.08, 10)).andExpect(jsonPath("$.aceptada").value(true));

        // signal-timeout = 2 s en esta prueba.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            trackingService.actualizarEstados();
            assertThat(estadoSesion(viaje)).isEqualTo("SIN_SENAL");
        });
        mvc.perform(get("/itineraries/{id}/bus-position", viaje.itineraryId()))
                .andExpect(jsonPath("$.estadoRastreo").value("SIN_SENAL"));

        enviar(conductor, lectura(hace(0), 4.6001, -74.08, 10)).andExpect(jsonPath("$.aceptada").value(true));
        assertThat(estadoSesion(viaje)).isEqualTo("ACTIVO");
        assertThat(estadosPublicados(viaje)).containsExactly("ACTIVO", "SIN_SENAL", "ACTIVO");
        String payload = jdbc.queryForObject("select payload from outbox where event_type = 'TrackingStatusChanged' "
                + "and payload like ? order by creado_en desc limit 1", String.class, "%" + viaje.itineraryId() + "%");
        assertThat(objectMapper.readTree(payload).fieldNames()).toIterable()
                .containsExactly("itineraryId", "busId", "companyId", "estado", "ultimaPosicionEn");
        assertThat(payload).contains(viaje.busId().toString(), companyId.toString());

        // En pausa no se pasa a SIN_SENAL.
        stop(conductor).andExpect(status().isOk());
        Thread.sleep(2500);
        trackingService.actualizarEstados();
        assertThat(estadoSesion(viaje)).isEqualTo("EN_PAUSA");
    }

    @Test
    void alTerminarLaVentanaOCancelarseLaSesionQuedaFinalizada() throws Exception {
        // Viaje cuya ventana ya terminó (llegó hace 1 h) con la sesión aún ACTIVO.
        Instant salida = Instant.now().minus(6, ChronoUnit.HOURS);
        Viaje terminado = publicar(salida, salida.plus(5, ChronoUnit.HOURS));
        UUID conductor = conductorEn(terminado);
        jdbc.update("insert into tracking_sessions (itinerary_id, conductor_id, estado, actualizado_en) "
                + "values (?, ?, 'ACTIVO', now())", terminado.itineraryId(), conductor);

        // Viaje en curso con la sesión encendida que luego se cancela.
        Viaje cancelado = enCurso();
        UUID otro = conductorEn(cancelado);
        start(otro).andExpect(status().isOk());
        cancelar(cancelado);

        assertThat(trackingService.actualizarEstados()).isGreaterThanOrEqualTo(2);
        assertThat(estadoSesion(terminado)).isEqualTo("FINALIZADO");
        assertThat(estadoSesion(cancelado)).isEqualTo("FINALIZADO");
        assertThat(estadosPublicados(terminado)).containsExactly("FINALIZADO");
        assertThat(estadosPublicados(cancelado)).containsExactly("ACTIVO", "FINALIZADO");

        // Desde ahí no se acepta nada más de ese viaje.
        enviar(conductor, lectura(hace(1), 4.6, -74.08, 10)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OUTSIDE_TRACKING_WINDOW"));
        start(otro).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("NO_ACTIVE_TRIP"));
        // Correr el job otra vez no vuelve a publicar.
        trackingService.actualizarEstados();
        assertThat(estadosPublicados(terminado)).hasSize(1);
    }

    @Test
    void unaSesionFinalizadaNoSeReabre() throws Exception {
        Viaje viaje = enCurso();
        UUID conductor = conductorEn(viaje);
        jdbc.update("insert into tracking_sessions (itinerary_id, conductor_id, estado, actualizado_en) "
                + "values (?, ?, 'FINALIZADO', now())", viaje.itineraryId(), conductor);

        start(conductor).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRACKING_NOT_ACTIVE"));
        stop(conductor).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRACKING_NOT_ACTIVE"));
        enviar(conductor, lectura(hace(1), 4.6, -74.08, 10)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRACKING_NOT_ACTIVE"));
        trackingService.markSimulated(viaje.itineraryId(), Instant.now(), 4.6, -74.08);
        assertThat(estadoSesion(viaje)).isEqualTo("FINALIZADO");
        assertThat(encoladas(viaje)).isZero();
    }

    @Test
    void pausarSinHaberEncendidoCreaLaSesionEnPausa() throws Exception {
        Viaje viaje = enCurso();
        UUID conductor = conductorEn(viaje);
        stop(conductor).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("EN_PAUSA"));
        assertThat(estadosPublicados(viaje)).containsExactly("EN_PAUSA");
        // El otro rol del mismo viaje también puede encenderla.
        UUID relevo = UUID.randomUUID();
        jdbc.update("insert into trip_assignments (id, itinerary_id, conductor_id, rol, asignado_por, asignado_en) "
                + "values (?, ?, ?, 'RELEVO', ?, now())", UUID.randomUUID(), viaje.itineraryId(), relevo, UUID.randomUUID());
        start(relevo).andExpect(jsonPath("$.estado").value("ACTIVO"));
        assertThat(jdbc.queryForObject("select conductor_id from tracking_sessions where itinerary_id = ?",
                UUID.class, viaje.itineraryId())).isEqualTo(relevo);
        assertThat(estadosPublicados(viaje).stream().collect(Collectors.joining(","))).isEqualTo("EN_PAUSA,ACTIVO");
    }
}
