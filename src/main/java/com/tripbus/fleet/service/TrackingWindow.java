package com.tripbus.fleet.service;

import com.tripbus.fleet.config.FleetProperties;
import com.tripbus.fleet.domain.FleetItinerary;
import com.tripbus.fleet.repository.FleetItineraryRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * US-039/US-047: el seguimiento es público, pero la posición del bus solo se muestra mientras
 * hace un viaje. Ventana activa de un itinerario: [fechaSalida - before, fechaLlegada + after],
 * nunca si está cancelado (tripbus.fleet.tracking-window-before / -after).
 */
@Component
public class TrackingWindow {

    private final FleetItineraryRepository itineraryRepository;
    private final FleetProperties properties;

    public TrackingWindow(FleetItineraryRepository itineraryRepository, FleetProperties properties) {
        this.itineraryRepository = itineraryRepository;
        this.properties = properties;
    }

    public Duration before() {
        return properties.trackingWindowBefore();
    }

    public Duration after() {
        return properties.trackingWindowAfter();
    }

    public Instant start(FleetItinerary itinerary) {
        return itinerary.getFechaSalida().minus(properties.trackingWindowBefore());
    }

    public Instant end(FleetItinerary itinerary) {
        return itinerary.getFechaLlegada().plus(properties.trackingWindowAfter());
    }

    public boolean isActive(FleetItinerary itinerary, Instant at) {
        return !itinerary.isCancelado() && !at.isBefore(start(itinerary)) && !at.isAfter(end(itinerary));
    }

    /** El itinerario del bus en ventana activa en ese instante (el de salida más temprana si hay varios). */
    public Optional<FleetItinerary> activeForBus(UUID busId, Instant at) {
        return itineraryRepository.findEnVentana(busId, at.plus(properties.trackingWindowBefore()),
                at.minus(properties.trackingWindowAfter())).stream().findFirst();
    }

    /**
     * El viaje en ventana activa al que pertenece la posición, si hay. Si trae itinerario se evalúa ese
     * (y debe ser de ese bus); si no, cualquier itinerario del bus en ventana.
     */
    public Optional<FleetItinerary> activeForPosition(UUID busId, UUID itineraryId, Instant at) {
        if (itineraryId == null) {
            return activeForBus(busId, at);
        }
        return itineraryRepository.findById(itineraryId)
                .filter(it -> it.getBusId().equals(busId))
                .filter(it -> isActive(it, at));
    }
}
