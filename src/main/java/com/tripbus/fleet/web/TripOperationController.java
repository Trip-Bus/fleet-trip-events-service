package com.tripbus.fleet.web;

import com.tripbus.fleet.common.security.CurrentUser;
import com.tripbus.fleet.domain.TripAlert;
import com.tripbus.fleet.service.TripAlertService;
import com.tripbus.fleet.service.TripProgressService;
import com.tripbus.fleet.web.dto.ResolveAlertRequest;
import com.tripbus.fleet.web.dto.TripAlertResponse;
import com.tripbus.fleet.web.dto.TripProgressResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** US-171 (fin forzado) y US-176 (alertas): OPERADOR de la empresa del viaje o ADMIN. */
@RestController
@PreAuthorize("hasAnyRole('OPERADOR', 'ADMIN')")
public class TripOperationController {

    private final TripProgressService progressService;
    private final TripAlertService alertService;

    public TripOperationController(TripProgressService progressService, TripAlertService alertService) {
        this.progressService = progressService;
        this.alertService = alertService;
    }

    /** US-171: forzar el fin del viaje (misma lógica que el conductor). */
    @PostMapping("/trips/{itineraryId}/finish")
    public TripProgressResponse finish(@PathVariable UUID itineraryId, Authentication authentication) {
        return progressService.finishByOperator(itineraryId, CurrentUser.id(authentication),
                CurrentUser.isAdmin(authentication), CurrentUser.companyId(authentication));
    }

    @GetMapping("/trips/{itineraryId}/alerts")
    public List<TripAlertResponse> alertsOfTrip(@PathVariable UUID itineraryId, Authentication authentication) {
        return alertService.alertsOfTrip(itineraryId, CurrentUser.isAdmin(authentication),
                CurrentUser.companyId(authentication));
    }

    @PostMapping("/trips/{itineraryId}/alerts/{alertId}/resolve")
    public TripAlertResponse resolve(@PathVariable UUID itineraryId, @PathVariable UUID alertId,
                                     @Valid @RequestBody(required = false) ResolveAlertRequest request,
                                     Authentication authentication) {
        return alertService.resolve(itineraryId, alertId, request != null ? request.nota() : null,
                CurrentUser.id(authentication), CurrentUser.isAdmin(authentication),
                CurrentUser.companyId(authentication));
    }

    /** Alertas de la empresa del operador (un ADMIN ve todas), opcionalmente por estado. */
    @GetMapping("/alerts")
    public List<TripAlertResponse> alerts(@RequestParam(required = false) TripAlert.Estado estado,
                                          Authentication authentication) {
        return alertService.alerts(estado, CurrentUser.isAdmin(authentication),
                CurrentUser.companyId(authentication));
    }
}
