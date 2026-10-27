package com.tripbus.fleet.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripbus.fleet.client.CatalogClient;
import com.tripbus.fleet.common.events.DomainEventHandler;
import com.tripbus.fleet.common.events.EventEnvelope;
import com.tripbus.fleet.repository.FleetItineraryRepository;
import com.tripbus.fleet.service.DriverAssignmentService;
import com.tripbus.fleet.service.RouteStopsCache;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * HT-16: mantiene la copia local de catalog al día. La idempotencia por eventId la da EventConsumer
 * (processed_events); además cada cambio es idempotente por sí mismo (reemplazar y actualizar).
 * Un itinerario que fleet no conoce (no llegó su ItineraryPublished) se ignora.
 */
@Component
public class CatalogSyncHandler implements DomainEventHandler {

    private final FleetItineraryRepository itineraryRepository;
    private final RouteStopsCache stopsCache;
    private final DriverAssignmentService assignmentService;

    public CatalogSyncHandler(FleetItineraryRepository itineraryRepository, RouteStopsCache stopsCache,
                              DriverAssignmentService assignmentService) {
        this.itineraryRepository = itineraryRepository;
        this.stopsCache = stopsCache;
        this.assignmentService = assignmentService;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(FleetEvents.ROUTE_CREATED, FleetEvents.ROUTE_UPDATED, FleetEvents.ITINERARY_RESCHEDULED,
                FleetEvents.ITINERARY_BUS_CHANGED);
    }

    @Override
    public void handle(EventEnvelope event) {
        JsonNode p = event.payload();
        switch (event.type()) {
            case FleetEvents.ROUTE_CREATED, FleetEvents.ROUTE_UPDATED ->
                    stopsCache.reemplazar(UUID.fromString(p.path("routeId").asText()), paradas(p.path("paradas")));
            case FleetEvents.ITINERARY_RESCHEDULED ->
                    itineraryRepository.findByIdForUpdate(UUID.fromString(p.path("itineraryId").asText()))
                            .ifPresent(itinerary -> {
                                itinerary.reprogramar(Instant.parse(p.path("fechaSalida").asText()),
                                        Instant.parse(p.path("fechaLlegada").asText()));
                                assignmentService.revisarCruces(itinerary);
                            });
            default -> // ITINERARY_BUS_CHANGED: la sesión de rastreo y las asignaciones se mantienen.
                    itineraryRepository.findById(UUID.fromString(p.path("itineraryId").asText()))
                            .ifPresent(itinerary -> itinerary.cambiarBus(UUID.fromString(p.path("busNuevo").asText())));
        }
    }

    private static List<CatalogClient.Stop> paradas(JsonNode paradas) {
        List<CatalogClient.Stop> stops = new ArrayList<>();
        for (JsonNode s : paradas) {
            stops.add(new CatalogClient.Stop(s.path("orden").asInt(), s.path("nombre").asText(),
                    s.path("lat").asDouble(), s.path("lng").asDouble(), s.path("minutosDesdeSalida").asInt()));
        }
        return stops;
    }
}
