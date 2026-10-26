package com.alejandro.bank.accounts.security;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.LinkedHashMap;
import java.util.Map;

// Errores en formato ProblemDetail (RFC 7807) con los mismos campos y en el mismo orden que Spring:
// detail, instance, status, title. El cliente no tiene que saber qué versión de accounts le respondió
public final class Problems {

    public static final String MEDIA_TYPE = "application/problem+json";

    private Problems() {
    }

    public static Response.ResponseBuilder of(int status, String title, String detail, UriInfo uri) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", detail);
        body.put("instance", "/" + uri.getPath().replaceFirst("^/", ""));
        body.put("status", status);
        body.put("title", title);
        return Response.status(status).type(MEDIA_TYPE).entity(body);
    }
}
