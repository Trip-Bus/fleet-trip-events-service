package com.tripbus.fleet.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripbus.fleet.common.events.DomainEventHandler;
import com.tripbus.fleet.common.events.EventEnvelope;
import com.tripbus.fleet.domain.FleetItinerary;
import com.tripbus.fleet.repository.FleetItineraryRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** ItineraryPublished (asociar bus ↔ itinerario) e ItineraryCancelled. */
@Component
public class FleetEventsHandler implements DomainEventHandler {

    private final FleetItineraryRepository repository;

    public FleetEventsHandler(FleetItineraryRepository repository) {
        this.repository = repository;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(FleetEvents.ITINERARY_PUBLISHED, FleetEvents.ITINERARY_CANCELLED);
    }

    @Override
    public void handle(EventEnvelope event) {
        JsonNode p = event.payload();
        UUID itineraryId = UUID.fromString(p.path("itineraryId").asText());
        if (FleetEvents.ITINERARY_PUBLISHED.equals(event.type())) {
            if (!repository.existsById(itineraryId)) {
                repository.save(new FleetItinerary(itineraryId,
                        UUID.fromString(p.path("busId").asText()),
                        UUID.fromString(p.path("routeId").asText()),
                        p.hasNonNull("companyId") ? UUID.fromString(p.get("companyId").asText()) : null,
                        p.path("origen").asText(null),
                        p.path("destino").asText(null),
                        Instant.parse(p.path("fechaSalida").asText()),
                        Instant.parse(p.path("fechaLlegada").asText())));
            }
        } else {
            repository.findById(itineraryId).ifPresent(FleetItinerary::cancelar);
        }
    }
}
