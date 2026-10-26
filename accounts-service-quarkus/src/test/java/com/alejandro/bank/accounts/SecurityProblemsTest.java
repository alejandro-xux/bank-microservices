package com.alejandro.bank.accounts;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

// 401 y 403 con el mismo ProblemDetail que accounts-service (los mismos textos y el mismo instance)
@QuarkusTest
class SecurityProblemsTest extends IntegrationTest {

    @Test
    void withoutTokenIs401ProblemDetail() {
        given().when().get("/accounts/me").then()
                .statusCode(401)
                .contentType("application/problem+json")
                .header("WWW-Authenticate", "Bearer")
                .body("title", equalTo("No autenticado"))
                .body("detail", equalTo("Falta el token o no es válido"))
                .body("status", equalTo(401))
                .body("instance", equalTo("/accounts/me"));
    }

    @Test
    void aTokenNotSignedByTheIssuerIs401() {
        given().header("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.firma").when().get("/accounts/me").then()
                .statusCode(401)
                .body("title", equalTo("No autenticado"));
    }

    @Test
    void wrongRoleIs403ProblemDetail() {
        given().header("Authorization", Tokens.admin()).when().get("/accounts/me").then()
                .statusCode(403)
                .contentType("application/problem+json")
                .body("title", equalTo("Acceso denegado"))
                .body("detail", equalTo("No tiene permiso para acceder a este recurso"))
                .body("status", equalTo(403))
                .body("instance", equalTo("/accounts/me"));
    }
}
