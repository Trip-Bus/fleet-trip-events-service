package com.tripbus.fleet.web;

import com.tripbus.fleet.common.security.CurrentUser;
import com.tripbus.fleet.service.FleetLiveService;
import com.tripbus.fleet.web.dto.FleetLiveResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** US-178: el mapa de la flota. OPERADOR (su empresa) o ADMIN (?companyId=, o todas sin él). */
@RestController
@PreAuthorize("hasAnyRole('OPERADOR', 'ADMIN')")
public class FleetLiveController {

    private final FleetLiveService fleetLiveService;

    public FleetLiveController(FleetLiveService fleetLiveService) {
        this.fleetLiveService = fleetLiveService;
    }

    /** El companyId de la consulta solo lo usa el ADMIN: el OPERADOR siempre ve la empresa de su token. */
    @GetMapping("/fleet/live")
    public FleetLiveResponse live(@RequestParam(required = false) UUID companyId, Authentication authentication) {
        return fleetLiveService.live(CurrentUser.isAdmin(authentication), CurrentUser.companyId(authentication),
                companyId);
    }
}
