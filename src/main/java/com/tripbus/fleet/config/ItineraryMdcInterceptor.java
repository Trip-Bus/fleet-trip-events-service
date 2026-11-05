package com.tripbus.fleet.config;

import com.tripbus.fleet.common.observability.TracePropagation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * HT-10: pone en el MDC el itineraryId de la ruta ({@code /itineraries/{id}/...}, {@code /trips/{id}/...} o
 * cualquier {@code {itineraryId}}) para que salga en cada línea de log de la petición. Solo UUID válidos
 * (nada que el cliente pueda usar para inyectar texto en los logs).
 */
@Configuration
public class ItineraryMdcInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private static final Pattern UUID_VALIDO =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String itineraryId = itineraryId(request);
        if (itineraryId != null) {
            MDC.put(TracePropagation.MDC_ITINERARY, itineraryId);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        MDC.remove(TracePropagation.MDC_ITINERARY);
    }

    static String itineraryId(HttpServletRequest request) {
        if (!(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> vars)) {
            return null;
        }
        Object valor = vars.get("itineraryId");
        String uri = request.getRequestURI();
        if (valor == null && (uri.startsWith("/itineraries/") || uri.startsWith("/trips/"))) {
            valor = vars.get("id");
        }
        return valor instanceof String s && UUID_VALIDO.matcher(s).matches() ? s : null;
    }
}
