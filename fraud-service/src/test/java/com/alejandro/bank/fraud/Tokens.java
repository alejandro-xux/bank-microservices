package com.alejandro.bank.fraud;

import io.smallrye.jwt.build.Jwt;

import java.util.List;

// Tokens firmados con la llave de FraudTestResource, con la forma de los de Keycloak (issuer, audience bank-api, roles)
public final class Tokens {

    private Tokens() {
    }

    public static String admin() {
        return "Bearer " + Jwt.issuer(FraudTestResource.ISSUER)
                .audience("bank-api")
                .subject("7b2e9d4c-1a5f-4e8b-b3c6-9f0a2d4e6b02")
                .claim("roles", List.of("ADMIN"))
                .expiresIn(300)
                .sign(FraudTestResource.KEYS.getPrivate());
    }
}
