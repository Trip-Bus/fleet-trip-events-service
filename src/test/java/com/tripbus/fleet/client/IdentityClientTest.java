package com.tripbus.fleet.client;

import com.tripbus.fleet.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IdentityClientTest {

    private static final String URL = "http://identity.test";

    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private MockRestServiceServer server;
    private IdentityClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = IdentityClient.builder(URL, "secreto");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new IdentityClient(builder);
    }

    @Test
    void devuelveElUsuarioYEnviaElTokenInterno() {
        server.expect(requestTo(URL + "/internal/users/" + userId))
                .andExpect(header("X-Internal-Token", "secreto"))
                .andRespond(withSuccess("""
                        {"id":"%s","nombre":"Ana","rol":"CONDUCTOR","companyId":"%s","activo":true,"extra":1}
                        """.formatted(userId, companyId), MediaType.APPLICATION_JSON));

        assertThat(client.user(userId)).contains(new IdentityClient.Usuario(userId, "Ana", "CONDUCTOR", companyId, true));
        server.verify();
    }

    @Test
    void siElUsuarioNoExisteDevuelveVacio() {
        server.expect(requestTo(URL + "/internal/users/" + userId)).andRespond(withResourceNotFound());

        assertThat(client.user(userId)).isEmpty();
    }

    @Test
    void siIdentityFallaResponde503() {
        server.expect(requestTo(URL + "/internal/users/" + userId)).andRespond(withServerError());

        assertThatThrownBy(() -> client.user(userId))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo("IDENTITY_UNAVAILABLE");
                });
    }

    @Test
    void siIdentityRechazaElTokenResponde503() {
        server.expect(requestTo(URL + "/internal/users/" + userId)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.user(userId)).isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "IDENTITY_UNAVAILABLE");
    }

    @Test
    void siIdentityRespondeSinCuerpoResponde503() {
        server.expect(requestTo(URL + "/internal/users/" + userId)).andRespond(withSuccess());

        assertThatThrownBy(() -> client.user(userId)).isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "IDENTITY_UNAVAILABLE");
    }

    @Test
    void sinIdentityLevantadoResponde503() {
        // Puerto 9 (discard) cerrado en localhost: la conexión se rechaza de inmediato.
        IdentityClient sinServidor = new IdentityClient("http://localhost:9", "secreto");

        assertThatThrownBy(() -> sinServidor.user(userId)).isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "IDENTITY_UNAVAILABLE");
    }
}
