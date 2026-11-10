package com.tripbus.fleet.web;

import com.tripbus.fleet.common.error.ApiException;
import com.tripbus.fleet.common.security.CurrentUser;
import com.tripbus.fleet.domain.TripEventType;
import com.tripbus.fleet.service.TelemetryService;
import com.tripbus.fleet.service.TripAlertService;
import com.tripbus.fleet.service.TripService;
import com.tripbus.fleet.web.dto.EtaView;
import com.tripbus.fleet.web.dto.ItineraryTracking;
import com.tripbus.fleet.web.dto.PositionRequest;
import com.tripbus.fleet.web.dto.PositionResponse;
import com.tripbus.fleet.web.dto.TripEventRequest;
import com.tripbus.fleet.web.dto.TripEventResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class FleetController {

    private final TelemetryService telemetryService;
    private final TripService tripService;
    private final TripAlertService alertService;

    public FleetController(TelemetryService telemetryService, TripService tripService, TripAlertService alertService) {
        this.telemetryService = telemetryService;
        this.tripService = tripService;
        this.alertService = alertService;
    }

    /**
     * US-038: ingesta de posición GPS. 202: se procesa de forma asíncrona. Solo ADMIN (pruebas
     * manuales): los conductores usan /driver/location (US-173).
     */
    @PostMapping("/telemetry/position")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> position(@Valid @RequestBody PositionRequest request) {
        telemetryService.validarItinerario(request);
        String eventId = telemetryService.ingest(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("eventId", eventId, "estado", "ENCOLADA"));
    }

    /**
     * Pública, pero solo mientras el bus hace un viaje (404 NO_ACTIVE_TRIP si no).
     * Deprecado: usar /itineraries/{id}/bus-position. Se retira en el Sprint 5.
     */
    @Deprecated
    @GetMapping("/buses/{id}/position")
    public PositionResponse busPosition(@PathVariable UUID id) {
        return tripService.busPosition(id);
    }

    /** US-039: dónde va mi bus. */
    @GetMapping("/itineraries/{id}/bus-position")
    public ItineraryTracking itineraryBusPosition(@PathVariable UUID id) {
        return tripService.tracking(id);
    }

    /** US-180: hora estimada por parada. Pública; 404 NO_ETA fuera de la ventana, cancelado o sin posiciones aún. */
    @GetMapping("/itineraries/{id}/eta")
    public EtaView eta(@PathVariable UUID id) {
        return tripService.eta(id)
                .orElseThrow(() -> ApiException.notFound("NO_ETA", "No hay hora estimada para este viaje ahora"));
    }

    /**
     * US-046: reportar una novedad del viaje (201 con la novedad). US-176: un SOS no va a la línea de
     * tiempo pública: crea (201) o devuelve la alerta SOS abierta del viaje (200).
     */
    @PostMapping("/trips/{id}/events")
    @PreAuthorize("hasAnyRole('CONDUCTOR', 'OPERADOR', 'ADMIN')")
    public ResponseEntity<Object> report(@PathVariable UUID id, @Valid @RequestBody TripEventRequest request,
                                         Authentication authentication) {
        TripService.Reportante quien = new TripService.Reportante(CurrentUser.id(authentication),
                CurrentUser.isAdmin(authentication), CurrentUser.isConductor(authentication),
                CurrentUser.companyId(authentication));
        if (request.tipo() == TripEventType.SOS) {
            tripService.autorizarReporte(id, quien);
            TripAlertService.SosResult sos = alertService.sosDelViaje(id, request.lat(), request.lng(),
                    request.descripcion(), quien.userId());
            return ResponseEntity.status(sos.nueva() ? HttpStatus.CREATED : HttpStatus.OK).body(sos.alerta());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(tripService.report(id, request, quien));
    }

    @GetMapping("/trips/{id}/timeline")
    public List<TripEventResponse> timeline(@PathVariable UUID id) {
        return tripService.timeline(id);
    }
}
