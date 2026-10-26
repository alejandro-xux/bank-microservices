package com.alejandro.bank.accounts;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

// Las rutas que usan el HEALTHCHECK de la imagen y los scripts, sin token, y la readiness incluye la base
@QuarkusTest
@QuarkusTestResource(AccountsTestResource.class)
class HealthTest {

    @Test
    void readinessIsUpAndChecksTheDatabase() {
        given().when().get("/actuator/health/readiness").then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.name", hasItem("Database connections health check"));
    }

    @Test
    void livenessIsUpWithoutToken() {
        given().when().get("/actuator/health/liveness").then().statusCode(200).body("status", equalTo("UP"));
    }
}
