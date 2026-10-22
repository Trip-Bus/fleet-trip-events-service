package com.tripbus.fleet.events;

public final class FleetEvents {

    public static final String POSITION_UPDATED = "PositionUpdated";
    public static final String TRIP_EVENT_REPORTED = "TripEventReported";
    public static final String DRIVER_ASSIGNED = "DriverAssigned";
    public static final String DRIVER_UNASSIGNED = "DriverUnassigned";
    public static final String TRACKING_STATUS_CHANGED = "TrackingStatusChanged";

    public static final String ITINERARY_PUBLISHED = "ItineraryPublished";
    public static final String ITINERARY_CANCELLED = "ItineraryCancelled";

    private FleetEvents() {
    }
}
