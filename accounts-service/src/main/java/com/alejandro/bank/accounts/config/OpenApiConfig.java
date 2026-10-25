package com.alejandro.bank.accounts.config;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ProblemDetail;
import org.springdoc.core.customizers.OpenApiCustomizer;

@Configuration
public class OpenApiConfig {

    static final String SECURITY_SCHEME = "oidc";

    // Botón "Authorize" de Swagger: descubre login y token en el emisor (Keycloak local o Entra en Azure).
    // El candado se aplica a todos los endpoints; health y la documentación no aparecen en Swagger.
    @Bean
    public OpenAPI accountsOpenApi(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.OPENIDCONNECT)
                        .openIdConnectUrl(issuerUri + "/.well-known/openid-configuration")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }

    // 401 y 403 en todos los endpoints, con el esquema ProblemDetail, sin escribirlos a mano en cada método.
    // putIfAbsent: respeta las descripciones propias de cada endpoint.
    @Bean
    public OpenApiCustomizer securityResponsesCustomizer() {
        return openApi -> {
            ModelConverters.getInstance().read(ProblemDetail.class).forEach(openApi.getComponents()::addSchemas);
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                operation.getResponses().putIfAbsent("401", problemResponse("Falta el token o no es válido"));
                operation.getResponses().putIfAbsent("403", problemResponse("No tiene permiso para acceder a este recurso"));
            }));
        };
    }

    private static ApiResponse problemResponse(String description) {
        Schema<?> problemDetail = new Schema<>().$ref("#/components/schemas/ProblemDetail");
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(
                        org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        new MediaType().schema(problemDetail)));
    }
}
