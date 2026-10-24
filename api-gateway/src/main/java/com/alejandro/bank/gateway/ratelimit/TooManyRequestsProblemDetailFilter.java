package com.alejandro.bank.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

// El RequestRateLimiter responde 429 con el cuerpo vacío (setComplete). Este filtro, que corre antes que todos,
// cambia ese cierre por un ProblemDetail como el resto de la API; los headers X-RateLimit-* se mantienen.
@Component
public class TooManyRequestsProblemDetailFilter implements GlobalFilter, Ordered {

    private final JsonMapper jsonMapper;

    public TooManyRequestsProblemDetailFilter(JsonMapper jsonMapper) {
        // Mismo mixin que usa Spring al responder ProblemDetail (omite "type" y "properties" vacíos)
        this.jsonMapper = jsonMapper.rebuild()
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                .build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        ServerHttpResponse response = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> setComplete() {
                if (!HttpStatus.TOO_MANY_REQUESTS.equals(getStatusCode())) {
                    return super.setComplete();
                }
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                        "Superó el límite de solicitudes; intente de nuevo en unos segundos");
                problem.setTitle("Demasiadas solicitudes");
                problem.setInstance(URI.create(path));
                getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
                byte[] body = jsonMapper.writeValueAsBytes(problem);
                return writeWith(Mono.just(bufferFactory().wrap(body)));
            }
        };
        return chain.filter(exchange.mutate().response(response).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
