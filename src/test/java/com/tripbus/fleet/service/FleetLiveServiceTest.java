package com.tripbus.fleet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tripbus.fleet.config.DetectionProperties;
import com.tripbus.fleet.domain.DriverRole;
import com.tripbus.fleet.domain.FleetItinerary;
import com.tripbus.fleet.domain.TrackingSession;
import com.tripbus.fleet.domain.TrackingStatus;
import com.tripbus.fleet.domain.TripAlert;
import com.tripbus.fleet.domain.TripAssignment;
import com.tripbus.fleet.domain.TripEventType;
import com.tripbus.fleet.repository.FleetItineraryRepository;
import com.tripbus.fleet.repository.TrackingSessionRepository;
import com.tripbus.fleet.repository.TripAlertRepository;
import com.tripbus.fleet.repository.TripAssignmentRepository;
import com.tripbus.fleet.web.dto.EtaView;
import com.tripbus.fleet.web.dto.FleetLiveResponse;
import com.tripbus.fleet.web.dto.PositionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FleetLiveServiceTest {

    private final Instant now = Instant.parse("2026-10-29T15:00:00Z");
    private final UUID empresa = UUID.randomUUID();
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private FleetItineraryRepository itineraries;
    private TrackingSessionRepository sessions;
    private TripAlertRepository alerts;
    private TripAssignmentRepository assignments;
    private DriverNameCache driverNames;
    private ValueOperations<String, String> valores;
    private FleetLiveService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        itineraries = mock(FleetItineraryRepository.class);
        sessions = mock(TrackingSessionRepository.class);
        alerts = mock(TripAlertRepository.class);
        assignments = mock(TripAssignmentRepository.class);
        driverNames = mock(DriverNameCache.class);
        TrackingWindow window = mock(TrackingWindow.class);
        when(window.before()).thenReturn(Duration.ofMinutes(30));
        when(window.after()).thenReturn(Duration.ofMinutes(30));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        valores = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valores);
        service = new FleetLiveService(itineraries, sessions, alerts, assignments, driverNames, window, redis,
                objectMapper, new DetectionProperties(null, null, null, null, null), Clock.fixed(now, ZoneOffset.UTC));
    }

    private FleetItinerary viaje() {
        return new FleetItinerary(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), empresa, "Bogotá", "Tunja",
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)));
    }

    private static TrackingSession sesion(FleetItinerary viaje, TrackingStatus estado) {
        TrackingSession sesion = mock(TrackingSession.class);
        when(sesion.getItineraryId()).thenReturn(viaje.getItineraryId());
        when(sesion.getEstado()).thenReturn(estado);
        return sesion;
    }

    private static TripAlert alerta(FleetItinerary viaje, TripEventType tipo) {
        return new TripAlert(viaje, tipo, null, null, "x", null, null, Instant.EPOCH);
    }

    private void empresaConViajes(FleetItinerary... viajes) {
        when(itineraries.findEnVentanaDeEmpresa(eq(empresa), any(), any(), any())).thenReturn(List.of(viajes));
    }

    private String json(Object valor) throws Exception {
        return objectMapper.writeValueAsString(valor);
    }

    @Test
    void armaCadaViajeYElResumenConConsultasEnBloque() throws Exception {
        FleetItinerary activo = viaje();
        activo.iniciar(UUID.randomUUID(), now.minus(Duration.ofMinutes(50)));
        FleetItinerary sinDatos = viaje();
        empresaConViajes(activo, sinDatos);
        List<TrackingSession> sesiones = List.of(sesion(activo, TrackingStatus.ACTIVO));
        when(sessions.findAllById(anyList())).thenReturn(sesiones);
        when(alerts.findByItineraryIdInAndEstado(anyList(), eq(TripAlert.Estado.ABIERTA)))
                .thenReturn(List.of(alerta(activo, TripEventType.AVERIA), alerta(activo, TripEventType.SOS)));
        UUID conductor = UUID.randomUUID();
        when(assignments.findByViajesYRol(anyList(), eq(DriverRole.PRINCIPAL)))
                .thenReturn(List.of(new TripAssignment(activo, conductor, DriverRole.PRINCIPAL, UUID.randomUUID(), now)));
        when(driverNames.nombres(any())).thenReturn(Map.of(conductor, "Ana"));
        PositionResponse posicion = new PositionResponse(activo.getBusId(), activo.getItineraryId(), 5.1, -73.6,
                72.0, 40.0, now.minusSeconds(5));
        EtaView eta = new EtaView(activo.getItineraryId(), 12, now, List.of(
                new EtaView.Parada(1, "Bogotá", now.minus(Duration.ofHours(1)), null, true),
                new EtaView.Parada(2, "Chocontá", now.plus(Duration.ofMinutes(10)), now.plus(Duration.ofMinutes(22)), false),
                new EtaView.Parada(3, "Tunja", now.plus(Duration.ofHours(1)), now.plus(Duration.ofMinutes(72)), false)));
        when(valores.multiGet(anyList())).thenReturn(Arrays.asList(json(posicion), json(eta), null, null));

        FleetLiveResponse respuesta = service.live(false, empresa, UUID.randomUUID());

        verify(itineraries).findEnVentanaDeEmpresa(eq(empresa), eq(now.plus(Duration.ofMinutes(30))),
                eq(now.minus(Duration.ofMinutes(30))), any(Pageable.class));
        verify(valores).multiGet(List.of(
                "fleet:itinerary:" + activo.getItineraryId() + ":last", "fleet:itinerary:" + activo.getItineraryId() + ":eta",
                "fleet:itinerary:" + sinDatos.getItineraryId() + ":last", "fleet:itinerary:" + sinDatos.getItineraryId() + ":eta"));
        assertThat(respuesta.generadoEn()).isEqualTo(now);
        assertThat(respuesta.resumen()).isEqualTo(new FleetLiveResponse.Resumen(2, 1, 1, 0, 0, 1, 1));

        FleetLiveResponse.Viaje primero = respuesta.viajes().get(0);
        assertThat(primero.estadoRastreo()).isEqualTo("ACTIVO");
        assertThat(primero.iniciado()).isTrue();
        assertThat(primero.posicion()).isEqualTo(new FleetLiveResponse.Posicion(5.1, -73.6, 72.0, 40.0,
                now.minusSeconds(5)));
        assertThat(primero.retrasoMinutos()).isEqualTo(12L);
        assertThat(primero.siguienteParada()).isEqualTo(new FleetLiveResponse.Parada(2, "Chocontá",
                now.plus(Duration.ofMinutes(10)), now.plus(Duration.ofMinutes(22))));
        assertThat(primero.ultimaParadaAlcanzada()).isEqualTo(new FleetLiveResponse.Parada(1, "Bogotá",
                now.minus(Duration.ofHours(1)), null));
        assertThat(primero.alertas()).isEqualTo(new FleetLiveResponse.Alertas(2, "CRITICA"));
        assertThat(primero.conductor()).isEqualTo(new FleetLiveResponse.Conductor(conductor, "Ana"));

        FleetLiveResponse.Viaje segundo = respuesta.viajes().get(1);
        assertThat(segundo.estadoRastreo()).isNull();
        assertThat(segundo.posicion()).isNull();
        assertThat(segundo.retrasoMinutos()).isNull();
        assertThat(segundo.siguienteParada()).isNull();
        assertThat(segundo.ultimaParadaAlcanzada()).isNull();
        assertThat(segundo.alertas()).isEqualTo(new FleetLiveResponse.Alertas(0, null));
        assertThat(segundo.conductor()).isNull();
    }

    @Test
    void cuentaCadaEstadoYLosRetrasosSoloSobreElUmbral() throws Exception {
        List<FleetItinerary> viajes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            viajes.add(viaje());
        }
        viajes.get(3).iniciar(UUID.randomUUID(), now);
        viajes.get(3).finalizar(now);
        empresaConViajes(viajes.toArray(FleetItinerary[]::new));
        List<TrackingSession> sesiones = List.of(sesion(viajes.get(0), TrackingStatus.EN_PAUSA),
                sesion(viajes.get(1), TrackingStatus.SIN_SENAL), sesion(viajes.get(2), TrackingStatus.SIN_SENAL),
                sesion(viajes.get(3), TrackingStatus.FINALIZADO));
        when(sessions.findAllById(anyList())).thenReturn(sesiones);
        // 10 min justos no cuenta como retrasado; 11 sí. Todas las paradas alcanzadas: sin siguiente.
        EtaView diez = new EtaView(viajes.get(0).getItineraryId(), 10, now, List.of());
        EtaView once = new EtaView(viajes.get(1).getItineraryId(), 11, now, List.of(
                new EtaView.Parada(1, "Bogotá", now, null, true), new EtaView.Parada(2, "Tunja", now, null, true)));
        when(valores.multiGet(anyList())).thenReturn(Arrays.asList(null, json(diez), "{no es json", json(once)));

        FleetLiveResponse respuesta = service.live(false, empresa, null);

        assertThat(respuesta.resumen()).isEqualTo(new FleetLiveResponse.Resumen(4, 0, 0, 1, 2, 1, 0));
        assertThat(respuesta.viajes().get(1).posicion()).isNull(); // valor ilegible
        assertThat(respuesta.viajes().get(1).siguienteParada()).isNull();
        assertThat(respuesta.viajes().get(1).ultimaParadaAlcanzada().nombre()).isEqualTo("Tunja");
        assertThat(respuesta.viajes().get(3).estadoRastreo()).isEqualTo("FINALIZADO");
    }

    @Test
    void sinRedisElMapaCargaSinPosiciones() {
        FleetItinerary viaje = viaje();
        empresaConViajes(viaje);
        when(valores.multiGet(anyList())).thenThrow(new RedisConnectionFailureException("caído"));

        FleetLiveResponse respuesta = service.live(false, empresa, null);

        assertThat(respuesta.viajes()).hasSize(1);
        assertThat(respuesta.viajes().get(0).posicion()).isNull();
        assertThat(respuesta.viajes().get(0).retrasoMinutos()).isNull();
    }

    @Test
    void redisSinRespuestaNoRompeElMapa() {
        empresaConViajes(viaje());
        when(valores.multiGet(anyList())).thenReturn(null);

        assertThat(service.live(false, empresa, null).viajes().get(0).posicion()).isNull();
    }

    @Test
    void unOperadorSinEmpresaNoVeNada() {
        FleetLiveResponse respuesta = service.live(false, null, empresa);

        assertThat(respuesta.viajes()).isEmpty();
        assertThat(respuesta.resumen()).isEqualTo(new FleetLiveResponse.Resumen(0, 0, 0, 0, 0, 0, 0));
        verifyNoInteractions(itineraries, sessions, alerts, assignments, driverNames, valores);
    }

    @Test
    void elAdminVeTodasOLaEmpresaQuePide() {
        UUID otra = UUID.randomUUID();
        when(itineraries.findEnVentanaTodas(any(), any(), any())).thenReturn(List.of());
        when(itineraries.findEnVentanaDeEmpresa(eq(otra), any(), any(), any())).thenReturn(List.of());

        assertThat(service.live(true, null, null).viajes()).isEmpty();
        verify(itineraries).findEnVentanaTodas(any(), any(), any());

        service.live(true, empresa, otra);
        verify(itineraries).findEnVentanaDeEmpresa(eq(otra), any(), any(), any());
        verify(itineraries, never()).findEnVentanaDeEmpresa(eq(empresa), any(), any(), any());
        verifyNoInteractions(valores);
    }
}
