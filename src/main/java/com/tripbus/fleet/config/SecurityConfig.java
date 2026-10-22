package com.tripbus.fleet.config;

import com.tripbus.fleet.common.security.InternalTokenFilter;
import com.tripbus.fleet.common.security.JsonSecurityHandlers;
import com.tripbus.fleet.common.security.JwtRoleConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JsonSecurityHandlers handlers,
            @Value("${tripbus.internal-token}") String internalToken) throws Exception {
        http
                // CSRF no aplica: API sin estado (STATELESS, sin sesión ni cookies). La autenticación va en
                // el header Authorization (JWT) o X-Internal-Token, que el navegador no envía solo en una
                // petición de otro sitio, así que no hay credencial ambiental que un atacante pueda usar.
                .csrf(AbstractHttpConfigurer::disable) // NOSONAR java:S4502: API REST sin estado con bearer token
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/internal/**").hasRole(InternalTokenFilter.INTERNAL_ROLE)
                        // Seguimiento del bus y línea de tiempo del viaje: lectura pública.
                        .requestMatchers(HttpMethod.GET, "/buses/*/position", "/itineraries/*/bus-position",
                                "/trips/*/timeline").permitAll()
                        // Swagger (en prod springdoc está apagado y estas rutas dan 404) y página de error.
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(new JwtRoleConverter()))
                        .authenticationEntryPoint(handlers.entryPoint()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(handlers.entryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()))
                .addFilterBefore(new InternalTokenFilter(internalToken), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
