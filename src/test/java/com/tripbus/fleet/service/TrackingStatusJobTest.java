package com.tripbus.fleet.service;

import com.tripbus.fleet.common.redis.LeaderLock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrackingStatusJobTest {

    @Test
    void elJobCorreEnUnSoloNodoConElLeaderLock() {
        LeaderLock leaderLock = mock(LeaderLock.class);
        DriverTrackingService trackingService = mock(DriverTrackingService.class);
        when(leaderLock.runIfLeader(eq("fleet-tracking-status"), eq(Duration.ofSeconds(14)), any()))
                .thenAnswer(inv -> {
                    inv.<Runnable>getArgument(2).run();
                    return true;
                });

        new TrackingStatusJob(leaderLock, trackingService).tick();

        verify(trackingService).actualizarEstados();
    }
}
