package com.alejandro.bank.gateway.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.security.oauth2.server.resource.web.access.server.BearerTokenServerAccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

// 401 y 403 en ProblemDetail, con el mismo formato que customers-service.
// Copia de customers-service (security/ProblemDetailSecurityHandlers): si más servicios la necesitan, pasa a una librería común.
// Los handlers estándar ponen el status y el header WWW-Authenticate; aquí solo se agrega el cuerpo.
// Mensajes genéricos, sin detalles técnicos del token en el cuerpo.
public class ProblemDetailSecurityHandlers {

    private final JsonMapper jsonMapper;
    private final BearerTokenServerAuthenticationEntryPoint bearerEntryPoint = new BearerTokenServerAuthenticationEntryPoint();
    private final BearerTokenServerAccessDeniedHandler bearerAccessDeniedHandler = new BearerTokenServerAccessDeniedHandler();

    public ProblemDetailSecurityHandlers(JsonMapper jsonMapper) {
        // Mismo mixin que usa Spring al responder ProblemDetail (omite "type" y "properties" vacíos)
        this.jsonMapper = jsonMapper.rebuild()
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                .build();
    }

    // 401: falta el token, o es inválido, vencido o de otra audiencia
    public ServerAuthenticationEntryPoint authenticationEntryPoint() {
        return (exchange, ex) -> bearerEntryPoint.commence(
                withProblemBody(exchange, "No autenticado", "Falta el token o no es válido"), ex);
    }

    // 403: el token es válido pero no tiene permiso (rol, dueño o /me)
    public ServerAccessDeniedHandler accessDeniedHandler() {
        return (exchange, ex) -> bearerAccessDeniedHandler.handle(
                withProblemBody(exchange, "Acceso denegado", "No tiene permiso para acceder a este recurso"), ex);
    }

    // El handler estándar termina con setComplete() (respuesta vacía); se cambia por escribir el ProblemDetail
    private ServerWebExchange withProblemBody(ServerWebExchange exchange, String title, String detail) {
        String path = exchange.getRequest().getPath().value();
        ServerHttpResponse response = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> setComplete() {
                HttpStatusCode status = getStatusCode() != null ? getStatusCode() : HttpStatus.UNAUTHORIZED;
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
                problem.setTitle(title);
                problem.setInstance(URI.create(path));
                getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
                byte[] body = jsonMapper.writeValueAsBytes(problem);
                return writeWith(Mono.just(bufferFactory().wrap(body)));
            }
        };
        return exchange.mutate().response(response).build();
    }
}
