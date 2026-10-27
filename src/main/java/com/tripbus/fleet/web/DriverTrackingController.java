package com.tripbus.fleet.web;

import com.tripbus.fleet.common.security.CurrentUser;
import com.tripbus.fleet.service.DriverTrackingService;
import com.tripbus.fleet.service.TripAlertService;
import com.tripbus.fleet.service.TripProgressService;
import com.tripbus.fleet.web.dto.BatchLocationResponse;
import com.tripbus.fleet.web.dto.DriverLocationRequest;
import com.tripbus.fleet.web.dto.LocationResult;
import com.tripbus.fleet.web.dto.SosRequest;
import com.tripbus.fleet.web.dto.StartTripRequest;
import com.tripbus.fleet.web.dto.TrackingStatusResponse;
import com.tripbus.fleet.web.dto.TripAlertResponse;
import com.tripbus.fleet.web.dto.TripProgressResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** US-173 a US-175: el conductor comparte la ubicación de su celular durante su viaje asignado. */
@RestController
@PreAuthorize("hasRole('CONDUCTOR')")
public class DriverTrackingController {

    static final int MAX_LOTE = 50;

    private final DriverTrackingService trackingService;
    private final TripProgressService progressService;
    private final TripAlertService alertService;

    public DriverTrackingController(DriverTrackingService trackingService, TripProgressService progressService,
                                    TripAlertService alertService) {
        this.trackingService = trackingService;
        this.progressService = progressService;
        this.alertService = alertService;
    }

    /** US-171: iniciar el viaje (checklist completo, dentro de la ventana, una sola vez). */
    @PostMapping("/driver/trips/{itineraryId}/start")
    public TripProgressResponse startTrip(@PathVariable UUID itineraryId, @Valid @RequestBody StartTripRequest request,
                                          Authentication authentication) {
        return progressService.start(itineraryId, CurrentUser.id(authentication), request.checklist(),
                request.observacion());
    }

    /** US-171: finalizar el viaje (solo si ya inició). */
    @PostMapping("/driver/trips/{itineraryId}/finish")
    public TripProgressResponse finishTrip(@PathVariable UUID itineraryId, Authentication authentication) {
        return progressService.finishByDriver(itineraryId, CurrentUser.id(authentication));
    }

    /** US-176: botón SOS. 201 si se creó la alerta; 200 si ya había una abierta para el viaje. */
    @PostMapping("/driver/sos")
    public ResponseEntity<TripAlertResponse> sos(@Valid @RequestBody(required = false) SosRequest request,
                                                 Authentication authentication) {
        SosRequest body = request != null ? request : new SosRequest(null, null, null);
        TripAlertService.SosResult sos = alertService.sos(CurrentUser.id(authentication), body.lat(), body.lng(),
                body.mensaje());
        return ResponseEntity.status(sos.nueva() ? HttpStatus.CREATED : HttpStatus.OK).body(sos.alerta());
    }

    @PostMapping("/driver/tracking/start")
    public TrackingStatusResponse start(Authentication authentication) {
        return trackingService.start(CurrentUser.id(authentication));
    }

    @PostMapping("/driver/tracking/stop")
    public TrackingStatusResponse stop(Authentication authentication) {
        return trackingService.stop(CurrentUser.id(authentication));
    }

    /** 202: aceptada se encola en la ingesta asíncrona; descartada trae aceptada=false y el motivo. */
    @PostMapping("/driver/location")
    public ResponseEntity<LocationResult> location(@Valid @RequestBody DriverLocationRequest request,
                                                   Authentication authentication) {
        LocationResult result = trackingService.location(CurrentUser.id(authentication), List.of(request)).get(0);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    /** Lo que el celular guardó sin conexión (hasta 50 lecturas), procesado en orden de timestamp. */
    @PostMapping("/driver/location/batch")
    public ResponseEntity<BatchLocationResponse> batch(
            @RequestBody @NotEmpty(message = "El lote no puede estar vacío")
            @Size(max = MAX_LOTE, message = "El lote admite hasta 50 lecturas")
            List<@Valid DriverLocationRequest> lecturas,
            Authentication authentication) {
        List<LocationResult> resultados = trackingService.location(CurrentUser.id(authentication), lecturas);
        int aceptadas = (int) resultados.stream().filter(LocationResult::aceptada).count();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new BatchLocationResponse(aceptadas, resultados.size() - aceptadas, resultados));
    }
}
