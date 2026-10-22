package com.tripbus.fleet.service;

import com.tripbus.fleet.common.redis.LeaderLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** US-174/US-175: cada 15 s, en un solo nodo, pasa las sesiones a SIN_SENAL o FINALIZADO. */
@Component
public class TrackingStatusJob {

    private final LeaderLock leaderLock;
    private final DriverTrackingService trackingService;

    public TrackingStatusJob(LeaderLock leaderLock, DriverTrackingService trackingService) {
        this.leaderLock = leaderLock;
        this.trackingService = trackingService;
    }

    @Scheduled(fixedDelayString = "${tripbus.jobs.tracking-status-ms:15000}",
            initialDelayString = "${tripbus.jobs.tracking-status-ms:15000}")
    public void tick() {
        leaderLock.runIfLeader("fleet-tracking-status", Duration.ofSeconds(14), trackingService::actualizarEstados);
    }
}
