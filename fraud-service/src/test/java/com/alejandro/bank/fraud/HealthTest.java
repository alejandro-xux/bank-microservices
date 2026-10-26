package com.alejandro.bank.fraud;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

// Las rutas que usan el HEALTHCHECK de la imagen y los scripts van sin token; todo lo demás pide un token válido
@QuarkusTest
@QuarkusTestResource(FraudTestResource.class)
public class HealthTest {

    @Test
    void readinessIsUpAndChecksTheDatabaseAndKafka() {
        given().when().get("/actuator/health/readiness").then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.name", hasItem("Database connections health check"))
                .body("checks.name", hasItem("SmallRye Reactive Messaging - readiness check"));
    }

    @Test
    void livenessIsUpWithoutToken() {
        given().when().get("/actuator/health/liveness").then().statusCode(200).body("status", equalTo("UP"));
    }

    @Test
    void anythingElseNeedsAToken() {
        given().when().get("/fraud/alerts").then().statusCode(401);
    }

    @Test
    void aValidTokenPassesTheCheck() {
        // Fraude no expone rutas todavía: con un token válido la respuesta es 404, no 401
        given().header("Authorization", Tokens.admin()).when().get("/fraud/alerts").then().statusCode(404);
    }
}
