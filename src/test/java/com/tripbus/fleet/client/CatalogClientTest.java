package com.tripbus.fleet.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.tripbus.fleet.config.FleetProperties;
import com.tripbus.fleet.config.RequestIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** Paradas de la ruta en catalog (GET /routes/{id}/stops), con el requestId propagado. */
class CatalogClientTest {

    private final WireMockServer catalog = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @BeforeEach
    void iniciar() {
        catalog.start();
    }

    @AfterEach
    void detener() {
        catalog.stop();
        MDC.clear();
    }

    @Test
    void devuelveLasParadasDeLaRuta() {
        UUID routeId = UUID.randomUUID();
        catalog.stubFor(get(urlEqualTo("/routes/" + routeId + "/stops")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        [{"orden":1,"nombre":"Bogotá","lat":4.65,"lng":-74.1,"minutosDesdeSalida":0},
                         {"orden":2,"nombre":"Tunja","lat":5.53,"lng":-73.36,"minutosDesdeSalida":150}]""")));
        FleetProperties properties = new FleetProperties(null, null, null, catalog.baseUrl(), false, null, null,
                null, null, null);
        CatalogClient client = new CatalogClient(properties, RestClient.builder());
        MDC.put(RequestIdFilter.MDC_KEY, "req-123");

        assertThat(client.stops(routeId)).containsExactly(
                new CatalogClient.Stop(1, "Bogotá", 4.65, -74.1, 0),
                new CatalogClient.Stop(2, "Tunja", 5.53, -73.36, 150));
        catalog.verify(getRequestedFor(urlEqualTo("/routes/" + routeId + "/stops"))
                .withHeader(RequestIdFilter.HEADER, equalTo("req-123")));
    }
}
