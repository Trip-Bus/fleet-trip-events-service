package com.tripbus.fleet.config;

import com.tripbus.fleet.common.observability.TracePropagation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ItineraryMdcInterceptorTest {

    private final ItineraryMdcInterceptor interceptor = new ItineraryMdcInterceptor();
    private final String id = UUID.randomUUID().toString();

    @AfterEach
    void limpiar() {
        MDC.clear();
    }

    private static MockHttpServletRequest request(String uri, Map<String, String> vars) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (vars != null) {
            request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, vars);
        }
        return request;
    }

    @Test
    void poneElItinerarioDeLaRutaEnElMdcYLoQuitaAlTerminar() {
        MockHttpServletRequest request = request("/itineraries/" + id + "/track", Map.of("id", id));

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(MDC.get(TracePropagation.MDC_ITINERARY)).isEqualTo(id);

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        assertThat(MDC.get(TracePropagation.MDC_ITINERARY)).isNull();
    }

    @Test
    void reconoceItineraryIdYTrips() {
        assertThat(ItineraryMdcInterceptor.itineraryId(
                request("/driver/trips/" + id + "/start", Map.of("itineraryId", id)))).isEqualTo(id);
        assertThat(ItineraryMdcInterceptor.itineraryId(request("/trips/" + id + "/timeline", Map.of("id", id))))
                .isEqualTo(id);
    }

    @Test
    void ignoraOtrosIdsValoresInvalidosYRutasSinVariables() {
        assertThat(ItineraryMdcInterceptor.itineraryId(request("/buses/" + id + "/position", Map.of("id", id))))
                .isNull();
        assertThat(ItineraryMdcInterceptor.itineraryId(request("/trips/x/timeline", Map.of("id", "x\ny"))))
                .isNull();
        assertThat(ItineraryMdcInterceptor.itineraryId(request("/fleet/live", null))).isNull();

        interceptor.preHandle(request("/fleet/live", null), new MockHttpServletResponse(), new Object());
        assertThat(MDC.get(TracePropagation.MDC_ITINERARY)).isNull();
    }
}
