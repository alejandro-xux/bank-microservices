package com.alejandro.bank.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

// Clave del balde de fichas (rate limiting): un balde por usuario, identificado por el claim oid del token
// (ya validado por el gateway, así que no se puede inventar). Si faltara oid, se usa sub.
@Component("userKeyResolver")
public class UserKeyResolver implements KeyResolver {

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(authentication -> {
                    String oid = authentication.getToken().getClaimAsString("oid");
                    return oid != null ? oid : authentication.getToken().getSubject();
                });
    }
}
