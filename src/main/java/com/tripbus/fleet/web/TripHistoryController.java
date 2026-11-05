package com.tripbus.fleet.web;

import com.tripbus.fleet.common.security.CurrentUser;
import com.tripbus.fleet.service.PunctualityReportService;
import com.tripbus.fleet.service.PunctualityService;
import com.tripbus.fleet.service.ShareLinkService;
import com.tripbus.fleet.web.dto.PunctualityReport;
import com.tripbus.fleet.web.dto.ShareLinkRequest;
import com.tripbus.fleet.web.dto.ShareLinkResponse;
import com.tripbus.fleet.web.dto.SharedTripView;
import com.tripbus.fleet.web.dto.TripTrackView;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** US-181 (compartir el viaje), US-182 (recorrido de un viaje terminado) y US-193 (reporte de puntualidad). */
@RestController
public class TripHistoryController {

    private final ShareLinkService shareLinkService;
    private final PunctualityService punctualityService;
    private final PunctualityReportService reportService;

    public TripHistoryController(ShareLinkService shareLinkService, PunctualityService punctualityService,
                                 PunctualityReportService reportService) {
        this.shareLinkService = shareLinkService;
        this.punctualityService = punctualityService;
        this.reportService = reportService;
    }

    /** US-181: cualquier usuario autenticado. 201 con el código. */
    @PostMapping("/share-links")
    @ResponseStatus(HttpStatus.CREATED)
    public ShareLinkResponse share(@Valid @RequestBody ShareLinkRequest request, Authentication authentication) {
        return shareLinkService.crear(request.itineraryId(), CurrentUser.id(authentication));
    }

    /** US-181: público. 404 SHARE_LINK_NOT_FOUND si venció o no existe. */
    @GetMapping("/share/{codigo}")
    public SharedTripView shared(@PathVariable String codigo) {
        return shareLinkService.ver(codigo);
    }

    /** US-182: público, solo viajes terminados (404 TRIP_NOT_FINISHED). */
    @GetMapping("/itineraries/{id}/track")
    public TripTrackView track(@PathVariable UUID id) {
        return punctualityService.track(id);
    }

    /** US-193: OPERADOR (su empresa) o ADMIN (?companyId=, o todas). */
    @GetMapping("/reports/punctuality")
    @PreAuthorize("hasAnyRole('OPERADOR', 'ADMIN')")
    public PunctualityReport punctuality(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                         @RequestParam(defaultValue = "ruta") String agrupar,
                                         @RequestParam(required = false) UUID companyId,
                                         Authentication authentication) {
        return reportService.report(desde, hasta, agrupar, companyId, CurrentUser.isAdmin(authentication),
                CurrentUser.companyId(authentication));
    }
}
