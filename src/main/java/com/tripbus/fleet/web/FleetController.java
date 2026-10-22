package com.tripbus.fleet.web;

import com.tripbus.fleet.common.security.CurrentUser;
import com.tripbus.fleet.service.TelemetryService;
import com.tripbus.fleet.service.TripService;
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

    public FleetController(TelemetryService telemetryService, TripService tripService) {
        this.telemetryService = telemetryService;
        this.tripService = tripService;
    }

    /** US-038: ingesta de posición GPS. 202: se procesa de forma asíncrona. */
    @PostMapping("/telemetry/position")
    @PreAuthorize("hasAnyRole('CONDUCTOR', 'OPERADOR', 'ADMIN')")
    public ResponseEntity<Map<String, String>> position(@Valid @RequestBody PositionRequest request,
                                                        Authentication authentication) {
        telemetryService.autorizar(request, CurrentUser.companyId(authentication),
                CurrentUser.hasRole(authentication, "ADMIN"));
        String eventId = telemetryService.ingest(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("eventId", eventId, "estado", "ENCOLADA"));
    }

    /** Pública, pero solo mientras el bus hace un viaje (404 NO_ACTIVE_TRIP si no). */
    @GetMapping("/buses/{id}/position")
    public PositionResponse busPosition(@PathVariable UUID id) {
        return tripService.busPosition(id);
    }

    /** US-039: dónde va mi bus. */
    @GetMapping("/itineraries/{id}/bus-position")
    public ItineraryTracking itineraryBusPosition(@PathVariable UUID id) {
        return tripService.tracking(id);
    }

    /** US-046: reportar una novedad del viaje. */
    @PostMapping("/trips/{id}/events")
    @PreAuthorize("hasAnyRole('CONDUCTOR', 'OPERADOR', 'ADMIN')")
    public ResponseEntity<TripEventResponse> report(@PathVariable UUID id, @Valid @RequestBody TripEventRequest request,
                                                    Authentication authentication) {
        TripEventResponse event = tripService.report(id, request.tipo(), request.descripcion(), request.detalle(),
                CurrentUser.id(authentication), CurrentUser.hasRole(authentication, "ADMIN"),
                CurrentUser.hasRole(authentication, "CONDUCTOR"), CurrentUser.companyId(authentication), "USUARIO");
        return ResponseEntity.status(HttpStatus.CREATED).body(event);
    }

    @GetMapping("/trips/{id}/timeline")
    public List<TripEventResponse> timeline(@PathVariable UUID id) {
        return tripService.timeline(id);
    }
}
