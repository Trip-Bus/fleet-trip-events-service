package com.tripbus.fleet.common.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeaderLockTest {

    private final Duration ventana = Duration.ofSeconds(4);
    private final AtomicInteger corridas = new AtomicInteger();
    private ValueOperations<String, String> valores;
    private LeaderLock lock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        valores = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valores);
        lock = new LeaderLock(redis);
    }

    @Test
    void soloCorreElJobQuienObtieneLaLlave() {
        when(valores.setIfAbsent(eq("job-lock:demo"), anyString(), eq(ventana))).thenReturn(true, false);

        assertThat(lock.runIfLeader("demo", ventana, corridas::incrementAndGet)).isTrue();
        assertThat(lock.runIfLeader("demo", ventana, corridas::incrementAndGet)).isFalse();
        assertThat(corridas).hasValue(1);
    }

    @Test
    void sinRedisNoCorreElJob() {
        when(valores.setIfAbsent(anyString(), anyString(), eq(ventana)))
                .thenThrow(new RedisConnectionFailureException("caído"));

        assertThat(lock.runIfLeader("demo", ventana, corridas::incrementAndGet)).isFalse();
        assertThat(corridas).hasValue(0);
    }

    @Test
    void unErrorDelJobNoSePropaga() {
        when(valores.setIfAbsent(anyString(), anyString(), eq(ventana))).thenReturn(true);

        assertThat(lock.runIfLeader("demo", ventana, () -> {
            throw new IllegalStateException("falla del job");
        })).isTrue();
    }
}
