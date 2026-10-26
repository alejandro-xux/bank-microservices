package com.alejandro.bank.accounts.security;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

// 401 y 403 con el mismo cuerpo que ProblemDetailSecurityHandlers de Spring. Funciona porque la autenticación no es
// proactiva (quarkus.http.auth.proactive=false): el fallo llega a JAX-RS como excepción y no lo contesta antes
// el mecanismo HTTP con un cuerpo vacío (spike BANK-98)
public class SecurityProblems {

    @ServerExceptionMapper({UnauthorizedException.class, AuthenticationFailedException.class})
    public Response unauthorized(UriInfo uri) {
        return Problems.of(401, "No autenticado", "Falta el token o no es válido", uri)
                .header("WWW-Authenticate", "Bearer").build();
    }

    @ServerExceptionMapper(ForbiddenException.class)
    public Response forbidden(UriInfo uri) {
        return Problems.of(403, "Acceso denegado", "No tiene permiso para acceder a este recurso", uri).build();
    }
}
