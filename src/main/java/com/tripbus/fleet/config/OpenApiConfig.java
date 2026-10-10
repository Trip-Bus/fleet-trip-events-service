package com.tripbus.fleet.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentación OpenAPI / Swagger UI (http://localhost:18005/swagger-ui.html). Solo en IDE y QA:
 * application-prod.yml apaga springdoc. La posición del bus y la línea de tiempo son públicas; para
 * /telemetry/** y POST /trips/{id}/events el botón "Authorize" acepta el accessToken que emite
 * identity en /auth/login (rol CONDUCTOR, OPERADOR o ADMIN).
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    public OpenAPI fleetOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "dev";
        return new OpenAPI()
                .info(new Info()
                        .title("TripBus · Fleet & Trip Events")
                        .description("Posiciones GPS de los buses, seguimiento del viaje y novedades (inicio, paradas, retrasos, desvíos, fin).")
                        .version(version))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
