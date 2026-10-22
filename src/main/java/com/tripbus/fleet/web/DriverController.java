package com.tripbus.fleet.web;

import com.tripbus.fleet.common.security.CurrentUser;
import com.tripbus.fleet.domain.DriverRole;
import com.tripbus.fleet.service.DriverAssignmentService;
import com.tripbus.fleet.service.DriverTripsService;
import com.tripbus.fleet.web.dto.AssignDriverRequest;
import com.tripbus.fleet.web.dto.DriverAssignmentResponse;
import com.tripbus.fleet.web.dto.DriverTripView;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** US-121 (conductores de cada viaje) y US-170 (mis viajes de hoy). */
@RestController
public class DriverController {

    private final DriverAssignmentService assignmentService;
    private final DriverTripsService driverTripsService;

    public DriverController(DriverAssignmentService assignmentService, DriverTripsService driverTripsService) {
        this.assignmentService = assignmentService;
        this.driverTripsService = driverTripsService;
    }

    /** US-121: asigna o reemplaza el conductor del rol (PRINCIPAL por defecto). */
    @PutMapping("/trips/{itineraryId}/driver")
    @PreAuthorize("hasAnyRole('OPERADOR', 'ADMIN')")
    public DriverAssignmentResponse assign(@PathVariable UUID itineraryId,
                                           @Valid @RequestBody AssignDriverRequest request,
                                           Authentication authentication) {
        DriverRole rol = request.rol() != null ? request.rol() : DriverRole.PRINCIPAL;
        return assignmentService.assign(itineraryId, request.conductorId(), rol, CurrentUser.id(authentication),
                CurrentUser.hasRole(authentication, "ADMIN"), CurrentUser.companyId(authentication));
    }

    @DeleteMapping("/trips/{itineraryId}/driver/{rol}")
    @PreAuthorize("hasAnyRole('OPERADOR', 'ADMIN')")
    public ResponseEntity<Void> unassign(@PathVariable UUID itineraryId, @PathVariable DriverRole rol,
                                         Authentication authentication) {
        assignmentService.unassign(itineraryId, rol, CurrentUser.hasRole(authentication, "ADMIN"),
                CurrentUser.companyId(authentication));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/trips/{itineraryId}/driver")
    @PreAuthorize("hasAnyRole('OPERADOR', 'ADMIN', 'CONDUCTOR')")
    public List<DriverAssignmentResponse> assigned(@PathVariable UUID itineraryId, Authentication authentication) {
        return assignmentService.assigned(itineraryId, CurrentUser.id(authentication),
                CurrentUser.hasRole(authentication, "ADMIN"), CurrentUser.hasRole(authentication, "CONDUCTOR"),
                CurrentUser.companyId(authentication));
    }

    /** US-170: mis viajes del día (por defecto hoy en America/Bogota). */
    @GetMapping("/driver/trips")
    @PreAuthorize("hasRole('CONDUCTOR')")
    public List<DriverTripView> myTrips(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            Authentication authentication) {
        return driverTripsService.trips(CurrentUser.id(authentication), fecha);
    }

    /** US-170: el viaje asignado que está ahora en su ventana, o 404 NO_ACTIVE_TRIP. */
    @GetMapping("/driver/trips/active")
    @PreAuthorize("hasRole('CONDUCTOR')")
    public DriverTripView activeTrip(Authentication authentication) {
        return driverTripsService.active(CurrentUser.id(authentication));
    }
}
