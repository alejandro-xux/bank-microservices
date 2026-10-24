package com.alejandro.bank.gateway.fallback;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.net.URI;
import java.util.Set;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR;

// Plan B del circuit breaker (fallbackUri: forward:/fallback/service-unavailable): servicio caído, lento,
// con 5xx o con el circuito abierto → 503 en ProblemDetail, sin detalles internos (ni host ni causa).
@RestController
public class ServiceUnavailableController {

    @RequestMapping("/fallback/service-unavailable")
    public ResponseEntity<ProblemDetail> serviceUnavailable(ServerWebExchange exchange) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "El servicio no está disponible en este momento; intente más tarde");
        problem.setTitle("Servicio no disponible");
        problem.setInstance(URI.create(originalPath(exchange)));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    // La ruta que pidió el cliente (p. ej. /api/customers/me), no la del forward interno
    private static String originalPath(ServerWebExchange exchange) {
        Set<URI> originals = exchange.getAttribute(GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        return originals == null || originals.isEmpty()
                ? exchange.getRequest().getPath().value()
                : originals.iterator().next().getPath();
    }
}
