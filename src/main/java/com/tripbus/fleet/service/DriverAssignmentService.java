package com.tripbus.fleet.service;

import com.tripbus.fleet.client.IdentityClient;
import com.tripbus.fleet.common.error.ApiException;
import com.tripbus.fleet.domain.DriverRole;
import com.tripbus.fleet.domain.FleetItinerary;
import com.tripbus.fleet.domain.TripAssignment;
import com.tripbus.fleet.events.FleetEvents;
import com.tripbus.fleet.repository.FleetItineraryRepository;
import com.tripbus.fleet.repository.TripAssignmentRepository;
import com.tripbus.fleet.web.dto.DriverAssignmentResponse;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * US-121: asignar conductores (PRINCIPAL y RELEVO) a los viajes. Lo hace un OPERADOR de la empresa
 * del viaje o un ADMIN. El conductor se valida en identity (rol, activo, empresa) y no puede tener
 * dos viajes cuyas ventanas de rastreo ({@link TrackingWindow}) se crucen. DriverAssigned y
 * DriverUnassigned van al outbox en la misma transacción.
 */
@Service
public class DriverAssignmentService {

    static final String ROL_CONDUCTOR = "CONDUCTOR";

    private final FleetItineraryRepository itineraryRepository;
    private final TripAssignmentRepository assignmentRepository;
    private final IdentityClient identityClient;
    private final TrackingWindow trackingWindow;
    private final Outbox outbox;
    private final EntityManager entityManager;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DriverAssignmentService(FleetItineraryRepository itineraryRepository,
                                   TripAssignmentRepository assignmentRepository, IdentityClient identityClient,
                                   TrackingWindow trackingWindow, Outbox outbox, EntityManager entityManager,
                                   TransactionTemplate tx, Clock clock) {
        this.itineraryRepository = itineraryRepository;
        this.assignmentRepository = assignmentRepository;
        this.identityClient = identityClient;
        this.trackingWindow = trackingWindow;
        this.outbox = outbox;
        this.entityManager = entityManager;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Asigna (o reemplaza) el conductor del rol indicado. La consulta a identity se hace antes de
     * abrir la transacción para no tener bloqueos tomados mientras se espera la red.
     */
    public DriverAssignmentResponse assign(UUID itineraryId, UUID conductorId, DriverRole rol, UUID userId,
                                           boolean isAdmin, UUID companyId) {
        FleetItinerary itinerary = itineraryRepository.findById(itineraryId)
                .orElseThrow(DriverAssignmentService::tripNotFound);
        validarAsignable(itinerary, isAdmin, companyId);
        validarConductor(conductorId, itinerary);
        return tx.execute(status -> guardar(itineraryId, conductorId, rol, userId, isAdmin, companyId));
    }

    private DriverAssignmentResponse guardar(UUID itineraryId, UUID conductorId, DriverRole rol, UUID userId,
                                             boolean isAdmin, UUID companyId) {
        // Bloquea el viaje: dos PUT sobre el mismo viaje y rol no chocan contra el único (viaje, rol).
        FleetItinerary itinerary = itineraryRepository.findByIdForUpdate(itineraryId)
                .orElseThrow(DriverAssignmentService::tripNotFound);
        validarAsignable(itinerary, isAdmin, companyId);

        TripAssignment actual = assignmentRepository.findByViajeYRol(itineraryId, rol).orElse(null);
        if (actual != null && actual.getConductorId().equals(conductorId)) {
            return toResponse(actual);
        }

        // Cruces: con el lock del conductor, la verificación y la inserción son atómicas frente a
        // otras asignaciones del mismo conductor en otros viajes.
        lockConductor(conductorId);
        assignmentRepository.findDelConductorEnVentana(conductorId,
                        trackingWindow.end(itinerary).plus(trackingWindow.before()),
                        trackingWindow.start(itinerary).minus(trackingWindow.after()))
                .stream().findFirst()
                .ifPresent(choque -> {
                    throw new ApiException(HttpStatus.CONFLICT, "DRIVER_SCHEDULE_CONFLICT",
                            "El conductor ya tiene un viaje asignado que se cruza con este",
                            Map.of("itineraryId", choque.getItinerary().getItineraryId()));
                });

        Instant now = clock.instant();
        TripAssignment assignment;
        if (actual == null) {
            assignment = assignmentRepository.save(new TripAssignment(itinerary, conductorId, rol, userId, now));
        } else {
            outbox.add(FleetEvents.DRIVER_UNASSIGNED, payload(itinerary, actual.getConductorId(), rol));
            actual.reasignar(conductorId, userId, now);
            assignment = actual;
        }
        outbox.add(FleetEvents.DRIVER_ASSIGNED, payload(itinerary, conductorId, rol));
        return toResponse(assignment);
    }

    @Transactional
    public void unassign(UUID itineraryId, DriverRole rol, boolean isAdmin, UUID companyId) {
        FleetItinerary itinerary = itineraryRepository.findById(itineraryId)
                .filter(it -> esDeSuEmpresa(it, isAdmin, companyId))
                .orElseThrow(DriverAssignmentService::tripNotFound);
        TripAssignment assignment = assignmentRepository.findByViajeYRol(itineraryId, rol)
                .orElseThrow(() -> ApiException.notFound("ASSIGNMENT_NOT_FOUND",
                        "El viaje no tiene conductor asignado con ese rol"));
        assignmentRepository.delete(assignment);
        outbox.add(FleetEvents.DRIVER_UNASSIGNED, payload(itinerary, assignment.getConductorId(), rol));
    }

    /** Conductores del viaje: OPERADOR de la empresa, ADMIN o un conductor asignado al viaje. */
    @Transactional(readOnly = true)
    public List<DriverAssignmentResponse> assigned(UUID itineraryId, UUID userId, boolean isAdmin,
                                                   boolean isConductor, UUID companyId) {
        FleetItinerary itinerary = itineraryRepository.findById(itineraryId)
                .orElseThrow(DriverAssignmentService::tripNotFound);
        List<TripAssignment> assignments = assignmentRepository.findByViaje(itineraryId);
        if (isConductor) {
            if (assignments.stream().noneMatch(a -> a.getConductorId().equals(userId))) {
                throw notAssigned();
            }
        } else if (!esDeSuEmpresa(itinerary, isAdmin, companyId)) {
            throw tripNotFound();
        }
        return assignments.stream().map(DriverAssignmentService::toResponse).toList();
    }

    /** ItineraryCancelled: el viaje cancelado se queda sin conductores. Requiere transacción. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void removeAll(FleetItinerary itinerary) {
        for (TripAssignment assignment : assignmentRepository.findByViaje(itinerary.getItineraryId())) {
            assignmentRepository.delete(assignment);
            outbox.add(FleetEvents.DRIVER_UNASSIGNED,
                    payload(itinerary, assignment.getConductorId(), assignment.getRol()));
        }
    }

    static ApiException notAssigned() {
        return ApiException.forbidden("NOT_ASSIGNED", "El conductor no está asignado a este viaje");
    }

    private void validarAsignable(FleetItinerary itinerary, boolean isAdmin, UUID companyId) {
        if (!esDeSuEmpresa(itinerary, isAdmin, companyId)) {
            throw tripNotFound();
        }
        if (itinerary.isCancelado()) {
            throw ApiException.conflict("TRIP_CANCELLED", "El viaje está cancelado");
        }
        if (clock.instant().isAfter(itinerary.getFechaLlegada())) {
            throw ApiException.conflict("TRIP_FINISHED", "El viaje ya terminó");
        }
    }

    private void validarConductor(UUID conductorId, FleetItinerary itinerary) {
        IdentityClient.Usuario conductor = identityClient.user(conductorId)
                .filter(u -> ROL_CONDUCTOR.equals(u.rol()))
                .orElseThrow(() -> ApiException.badRequest("INVALID_DRIVER", "El usuario no es un conductor"));
        if (!conductor.activo()) {
            throw ApiException.badRequest("DRIVER_INACTIVE", "El conductor no está activo");
        }
        if (!Objects.equals(conductor.companyId(), itinerary.getCompanyId())) {
            throw ApiException.badRequest("DRIVER_OTHER_COMPANY", "El conductor no es de la empresa del viaje");
        }
    }

    /** pg_advisory_xact_lock: se libera solo al terminar la transacción (commit o rollback). */
    private void lockConductor(UUID conductorId) {
        long key = conductorId.getMostSignificantBits() ^ conductorId.getLeastSignificantBits();
        entityManager.createNativeQuery("select 1 from (select pg_advisory_xact_lock(?1)) l")
                .setParameter(1, key)
                .getSingleResult();
    }

    private static boolean esDeSuEmpresa(FleetItinerary itinerary, boolean isAdmin, UUID companyId) {
        return isAdmin || (companyId != null && companyId.equals(itinerary.getCompanyId()));
    }

    private static ApiException tripNotFound() {
        return ApiException.notFound("TRIP_NOT_FOUND", "El viaje no existe");
    }

    private static Map<String, Object> payload(FleetItinerary itinerary, UUID conductorId, DriverRole rol) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("itineraryId", itinerary.getItineraryId());
        payload.put("companyId", itinerary.getCompanyId());
        payload.put("conductorId", conductorId);
        payload.put("rol", rol.name());
        return payload;
    }

    private static DriverAssignmentResponse toResponse(TripAssignment a) {
        return new DriverAssignmentResponse(a.getItinerary().getItineraryId(), a.getConductorId(), a.getRol().name(),
                a.getAsignadoPor(), a.getAsignadoEn());
    }
}
