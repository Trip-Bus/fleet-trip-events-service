package com.tripbus.fleet.repository;

import com.tripbus.fleet.domain.TrackingSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrackingSessionRepository extends JpaRepository<TrackingSession, UUID> {

    /** Bloquea la sesión: las lecturas de un viaje y los cambios del job se hacen una tras otra. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TrackingSession s where s.itineraryId = :id")
    Optional<TrackingSession> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Crea la sesión EN_PAUSA si el viaje aún no tiene (ON CONFLICT: dos peticiones simultáneas no
     * chocan contra la llave). Devuelve 1 si la creó.
     */
    @Modifying
    @Query(value = """
            insert into tracking_sessions (itinerary_id, conductor_id, estado, actualizado_en)
            values (:id, :conductorId, 'EN_PAUSA', :now)
            on conflict (itinerary_id) do nothing
            """, nativeQuery = true)
    int crearSiNoExiste(@Param("id") UUID id, @Param("conductorId") UUID conductorId, @Param("now") Instant now);

    /** Igual que {@link #crearSiNoExiste}, sin conductor (simulador GPS). */
    @Modifying
    @Query(value = """
            insert into tracking_sessions (itinerary_id, estado, actualizado_en)
            values (:id, 'EN_PAUSA', :now)
            on conflict (itinerary_id) do nothing
            """, nativeQuery = true)
    int crearSimuladaSiNoExiste(@Param("id") UUID id, @Param("now") Instant now);

    /** Sesiones que el job puede tener que pasar a SIN_SENAL o FINALIZADO. */
    @Query("select s.itineraryId from TrackingSession s where s.estado <> com.tripbus.fleet.domain.TrackingStatus.FINALIZADO")
    List<UUID> findAbiertas();
}
