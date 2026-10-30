package com.tripbus.fleet.service;

import com.tripbus.fleet.client.IdentityClient;
import com.tripbus.fleet.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriverNameCacheTest {

    private final Instant inicio = Instant.parse("2026-10-29T12:00:00Z");
    private final UUID ana = UUID.randomUUID();
    private IdentityClient identity;
    private Clock clock;
    private DriverNameCache cache;

    @BeforeEach
    void setUp() {
        identity = mock(IdentityClient.class);
        clock = mock(Clock.class);
        when(clock.instant()).thenReturn(inicio);
        cache = new DriverNameCache(identity, clock);
    }

    private static IdentityClient.Usuario usuario(UUID id, String nombre) {
        return new IdentityClient.Usuario(id, nombre, "CONDUCTOR", UUID.randomUUID(), true);
    }

    @Test
    void guardaElNombreDiezMinutos() {
        when(identity.user(ana)).thenReturn(Optional.of(usuario(ana, "Ana")));

        assertThat(cache.nombres(List.of(ana))).containsEntry(ana, "Ana");
        when(clock.instant()).thenReturn(inicio.plus(DriverNameCache.TTL).minusSeconds(1));
        assertThat(cache.nombres(List.of(ana))).containsEntry(ana, "Ana");
        verify(identity, times(1)).user(ana);

        // Vencida la caché se vuelve a consultar.
        when(clock.instant()).thenReturn(inicio.plus(DriverNameCache.TTL));
        when(identity.user(ana)).thenReturn(Optional.of(usuario(ana, "Ana María")));
        assertThat(cache.nombres(List.of(ana))).containsEntry(ana, "Ana María");
        verify(identity, times(2)).user(ana);
    }

    @Test
    void unConductorQueIdentityNoConoceQuedaSinNombre() {
        when(identity.user(ana)).thenReturn(Optional.empty());

        assertThat(cache.nombres(List.of(ana))).containsEntry(ana, null);
        assertThat(cache.nombres(List.of(ana))).containsEntry(ana, null);
        verify(identity, times(1)).user(ana);
    }

    @Test
    void conIdentityCaidoElNombreEsNullYNoSeInsisteHastaLaPausa() {
        UUID beto = UUID.randomUUID();
        when(identity.user(any())).thenThrow(ApiException.unavailable("IDENTITY_UNAVAILABLE", "caído"));

        assertThat(cache.nombres(List.of(ana, beto))).containsEntry(ana, null).containsEntry(beto, null);
        // Solo el primero llegó a identity: el resto se salta durante la pausa.
        verify(identity, times(1)).user(any());

        when(clock.instant()).thenReturn(inicio.plus(DriverNameCache.PAUSA_TRAS_FALLA).minusSeconds(1));
        cache.nombres(List.of(beto));
        verify(identity, times(1)).user(any());

        // Pasada la pausa, identity responde otra vez.
        when(clock.instant()).thenReturn(inicio.plus(DriverNameCache.PAUSA_TRAS_FALLA));
        doReturn(Optional.of(usuario(beto, "Beto"))).when(identity).user(beto);
        assertThat(cache.nombres(List.of(beto))).containsEntry(beto, "Beto");
    }

    @Test
    void limpiaLasEntradasVencidasCuandoCreceDemasiado() {
        when(identity.user(any())).thenReturn(Optional.empty());
        List<UUID> muchos = new ArrayList<>();
        for (int i = 0; i <= DriverNameCache.LIMPIAR_DESDE; i++) {
            muchos.add(UUID.randomUUID());
        }
        cache.nombres(muchos);

        // Todas vencieron: se limpian y ana se consulta normal.
        when(clock.instant()).thenReturn(inicio.plus(DriverNameCache.TTL).plusSeconds(1));
        when(identity.user(ana)).thenReturn(Optional.of(usuario(ana, "Ana")));
        assertThat(cache.nombres(List.of(ana))).containsEntry(ana, "Ana");
        // Una vencida que se vuelve a pedir llega otra vez a identity.
        cache.nombres(List.of(muchos.get(0)));
        verify(identity, times(2)).user(muchos.get(0));
        verify(identity, never()).user((UUID) null);
    }
}
