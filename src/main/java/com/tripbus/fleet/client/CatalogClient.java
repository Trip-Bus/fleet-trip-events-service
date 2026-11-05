package com.tripbus.fleet.client;

import com.tripbus.fleet.config.FleetProperties;
import com.tripbus.fleet.config.RequestIdPropagationInterceptor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

@Component
public class CatalogClient {

    private final RestClient restClient;

    /**
     * HT-10: parte del RestClient.Builder de Spring Boot (con observación: cada llamada crea un span y
     * propaga la traza en el header traceparent), además del requestId.
     */
    public CatalogClient(FleetProperties properties, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(3000);
        this.restClient = builder
                .baseUrl(properties.catalogUrl())
                .requestFactory(factory)
                .requestInterceptor(new RequestIdPropagationInterceptor())
                .build();
    }

    public record Stop(int orden, String nombre, double lat, double lng, int minutosDesdeSalida) {
    }

    public List<Stop> stops(UUID routeId) {
        return restClient.get().uri("/routes/{id}/stops", routeId).retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }
}
