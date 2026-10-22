package com.tripbus.fleet.client;

import com.tripbus.fleet.common.error.ApiException;
import com.tripbus.fleet.common.security.InternalTokenFilter;
import com.tripbus.fleet.config.RequestIdPropagationInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * US-121: consulta un usuario en identity ({@code GET /internal/users/{id}}, header X-Internal-Token)
 * para validar al conductor que se asigna a un viaje. Timeouts cortos (300 ms conexión / 800 ms
 * lectura): si identity no responde, 503 IDENTITY_UNAVAILABLE y no se asigna.
 */
@Component
public class IdentityClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofMillis(300);
    static final Duration READ_TIMEOUT = Duration.ofMillis(800);

    private static final Logger log = LoggerFactory.getLogger(IdentityClient.class);

    /** Respuesta de identity: {@code {id, nombre, rol, companyId, activo}}. */
    public record Usuario(UUID id, String nombre, String rol, UUID companyId, boolean activo) {
    }

    private final RestClient restClient;

    @Autowired
    public IdentityClient(@Value("${tripbus.services.identity-url}") String identityUrl,
                          @Value("${tripbus.internal-token}") String internalToken) {
        this(builder(identityUrl, internalToken));
    }

    /** Las pruebas enlazan un MockRestServiceServer al builder antes de construir el cliente. */
    IdentityClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    static RestClient.Builder builder(String identityUrl, String internalToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(identityUrl)
                .requestFactory(factory)
                .defaultHeader(InternalTokenFilter.HEADER, internalToken)
                .requestInterceptor(new RequestIdPropagationInterceptor());
    }

    /**
     * @return el usuario, o vacío si identity responde 404
     * @throws ApiException 503 IDENTITY_UNAVAILABLE si identity no responde a tiempo o falla
     */
    public Optional<Usuario> user(UUID id) {
        Usuario usuario;
        try {
            usuario = restClient.get().uri("/internal/users/{id}", id).retrieve().body(Usuario.class);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("No se pudo consultar el usuario {} en identity: {}", id, e.getMessage());
            throw unavailable();
        }
        if (usuario == null) {
            log.warn("Identity respondió sin cuerpo al consultar el usuario {}", id);
            throw unavailable();
        }
        return Optional.of(usuario);
    }

    private static ApiException unavailable() {
        return ApiException.unavailable("IDENTITY_UNAVAILABLE", "No fue posible validar el conductor en identity");
    }
}
