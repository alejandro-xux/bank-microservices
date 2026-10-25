package com.alejandro.bank.accounts.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;

// 401 y 403 en ProblemDetail, con el mismo formato que customers-service (versión Spring MVC).
// Los handlers estándar ponen el status y el header WWW-Authenticate; aquí solo se agrega el cuerpo.
public class ProblemDetailSecurityHandlers {

    private final JsonMapper jsonMapper;
    private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler bearerAccessDeniedHandler = new BearerTokenAccessDeniedHandler();

    public ProblemDetailSecurityHandlers(JsonMapper jsonMapper) {
        // Mismo mixin que usa Spring al responder ProblemDetail (omite "type" y "properties" vacíos)
        this.jsonMapper = jsonMapper.rebuild()
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                .build();
    }

    // 401: falta el token, o es inválido, vencido o de otra audiencia
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, ex) -> {
            bearerEntryPoint.commence(request, response, ex);
            writeProblem(request, response, "No autenticado", "Falta el token o no es válido");
        };
    }

    // 403: el token es válido pero no tiene permiso
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> {
            bearerAccessDeniedHandler.handle(request, response, ex);
            writeProblem(request, response, "Acceso denegado", "No tiene permiso para acceder a este recurso");
        };
    }

    private void writeProblem(HttpServletRequest request, HttpServletResponse response,
                              String title, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(response.getStatus()), detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write(jsonMapper.writeValueAsBytes(problem));
    }
}
