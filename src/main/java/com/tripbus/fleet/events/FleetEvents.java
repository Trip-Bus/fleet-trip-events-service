package com.tripbus.fleet.events;

public final class FleetEvents {

    public static final String POSITION_UPDATED = "PositionUpdated";
    public static final String TRIP_EVENT_REPORTED = "TripEventReported";
    public static final String DRIVER_ASSIGNED = "DriverAssigned";
    public static final String DRIVER_UNASSIGNED = "DriverUnassigned";
    public static final String TRACKING_STATUS_CHANGED = "TrackingStatusChanged";
    public static final String DRIVER_ASSIGNMENT_CONFLICT = "DriverAssignmentConflict";
    public static final String TRIP_ALERT_RAISED = "TripAlertRaised";
    public static final String TRIP_ALERT_RESOLVED = "TripAlertResolved";
    public static final String TRIP_INTERRUPTED = "TripInterrupted";

    // Consumidos de catalog.
    public static final String ITINERARY_PUBLISHED = "ItineraryPublished";
    public static final String ITINERARY_CANCELLED = "ItineraryCancelled";
    public static final String ITINERARY_RESCHEDULED = "ItineraryRescheduled";
    public static final String ITINERARY_BUS_CHANGED = "ItineraryBusChanged";
    public static final String ROUTE_CREATED = "RouteCreated";
    public static final String ROUTE_UPDATED = "RouteUpdated";

    private FleetEvents() {
    }
}
