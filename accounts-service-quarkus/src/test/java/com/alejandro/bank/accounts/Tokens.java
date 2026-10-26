package com.alejandro.bank.accounts;

import io.smallrye.jwt.build.Jwt;

import java.util.List;

// Tokens de las pruebas, con la forma de los de Keycloak/Entra (roles, oid, aud=bank-api), firmados con la llave de
// AccountsTestResource. El oid decide qué responde el customers falso
public final class Tokens {

    public static final String ANA_OID = "4f8c2a1e-6b3d-4c9a-9e7f-2d5b8a1c3e01";
    public static final String LUIS_OID = "5a1d3c2b-7e4f-4a6b-8c9d-0e1f2a3b4c55";
    // Un cliente sin ficha vinculada: customers responde PERMISSION_DENIED
    public static final String UNLINKED_OID = "6b2e4d3c-8f5a-4b7c-9d0e-1f2a3b4c5d66";
    // customers-service caído para este usuario: UNAVAILABLE
    public static final String CUSTOMERS_DOWN_OID = "7c3f5e4d-9a6b-4c8d-0e1f-2a3b4c5d6e77";

    private Tokens() {
    }

    public static String customer(String oid) {
        return bearer(oid, "CUSTOMER");
    }

    public static String admin() {
        return bearer("7b2e9d4c-1a5f-4e8b-b3c6-9f0a2d4e6b02", "ADMIN");
    }

    private static String bearer(String oid, String role) {
        return "Bearer " + Jwt.issuer(AccountsTestResource.ISSUER)
                .audience("bank-api")
                .subject(oid)
                .claim("oid", oid)
                .claim("roles", List.of(role))
                .expiresIn(300)
                .sign(AccountsTestResource.KEYS.getPrivate());
    }
}
