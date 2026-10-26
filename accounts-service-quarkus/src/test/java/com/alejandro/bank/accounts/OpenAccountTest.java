package com.alejandro.bank.accounts;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.containsString;

// Apertura de cuentas, los mismos casos que OpenAccountIntegrationTest (BANK-53)
@QuarkusTest
class OpenAccountTest extends IntegrationTest {

    @BeforeEach
    void clean() {
        emptyBank();
    }

    @Test
    void adminOpensAnAccountWithZeroBalance() {
        String id = open(Tokens.admin(), FakeCustomers.ANA, "AHORROS", "PEN").statusCode(201)
                .body("number", matchesPattern("001-\\d{10}-\\d"))
                .body("customerId", equalTo(FakeCustomers.ANA))
                .body("type", equalTo("AHORROS"))
                .body("currency", equalTo("PEN"))
                .body("balance", equalTo("0.00"))
                .body("status", equalTo("ACTIVE"))
                // Relativo, como ResponseEntity.created de Spring
                .header("Location", matchesPattern("/accounts/[0-9a-f-]{36}"))
                .extract().path("id");
        assertThat(queryString("select balance::text from account where id = ?::uuid", id)).isEqualTo("0.00");
    }

    @Test
    void eachAccountGetsADifferentNumber() {
        String first = open(Tokens.admin(), FakeCustomers.ANA, "AHORROS", "PEN").statusCode(201).extract().path("number");
        String second = open(Tokens.admin(), FakeCustomers.ANA, "CORRIENTE", "USD").statusCode(201).extract().path("number");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void customerCannotOpenAccounts() {
        open(Tokens.customer(Tokens.ANA_OID), FakeCustomers.ANA, "AHORROS", "PEN").statusCode(403)
                .body("title", equalTo("Acceso denegado"));
        assertThat(queryString("select count(*)::text from account")).isEqualTo("0");
    }

    @Test
    void unknownCustomerIs422() {
        open(Tokens.admin(), FakeCustomers.MISSING, "AHORROS", "PEN").statusCode(422)
                .body("title", equalTo("Cliente no válido"))
                .body("detail", containsString("no existe"));
    }

    @Test
    void blockedCustomerIs422() {
        open(Tokens.admin(), FakeCustomers.BLOCKED, "AHORROS", "PEN").statusCode(422)
                .body("detail", containsString("bloqueado"));
    }

    @Test
    void customersServiceDownIs503AndNothingIsWritten() {
        open(Tokens.admin(), FakeCustomers.DOWN, "AHORROS", "PEN").statusCode(503)
                .body("title", equalTo("Servicio no disponible"));
        assertThat(queryString("select count(*)::text from account")).isEqualTo("0");
    }

    @Test
    void slowCustomersServiceIsCutAtTheDeadline() {
        long start = System.nanoTime();
        open(Tokens.admin(), FakeCustomers.SLOW, "AHORROS", "PEN").statusCode(503);
        assertThat((System.nanoTime() - start) / 1_000_000).as("ms hasta el 503").isLessThan(2_900);
    }

    @Test
    void invalidFieldsAre400WithTheirMessages() {
        given().header("Authorization", Tokens.admin()).contentType("application/json").body("{\"customerId\":\"x\"}")
                .when().post("/accounts").then().statusCode(400)
                .contentType("application/problem+json")
                .body("title", equalTo("Datos inválidos"))
                .body("errors.find { it.field == 'customerId' }.message", equalTo("debe ser un UUID"))
                .body("errors.find { it.field == 'type' }.message", equalTo("es obligatorio"));
    }

    @Test
    void unknownCurrencyIs400MalformedRequest() {
        open(Tokens.admin(), FakeCustomers.ANA, "AHORROS", "EUR").statusCode(400)
                .body("title", equalTo("Solicitud mal formada"));
    }

    private static ValidatableResponse open(String token, String customerId, String type, String currency) {
        return given().header("Authorization", token).contentType("application/json")
                .body("{\"customerId\":\"" + customerId + "\",\"type\":\"" + type + "\",\"currency\":\"" + currency + "\"}")
                .when().post("/accounts").then();
    }
}
