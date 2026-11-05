package com.tripbus.fleet.events;

import com.tripbus.fleet.common.events.DomainEventHandler;
import com.tripbus.fleet.common.events.EventEnvelope;
import com.tripbus.fleet.service.DriverAssignmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * HT-10: UserDeleted {userId} de identity. Si era conductor, sale de sus asignaciones en viajes que aún no
 * terminan (con DriverAssignmentConflict para que el operador asigne otro). Si no tenía, no pasa nada.
 */
@Component
public class IdentityEventsHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(IdentityEventsHandler.class);

    private final DriverAssignmentService assignmentService;

    public IdentityEventsHandler(DriverAssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(FleetEvents.USER_DELETED);
    }

    @Override
    public void handle(EventEnvelope event) {
        UUID userId = UUID.fromString(event.payload().path("userId").asText());
        int quitadas = assignmentService.quitarConductorEliminado(userId);
        if (quitadas > 0) {
            log.info("Usuario eliminado {}: se quitó de {} viajes asignados", userId, quitadas);
        }
    }
}
