package com.alejandro.bank.accounts;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

// Las mismas consultas que AccountQueriesIntegrationTest (BANK-54): /me, una cuenta con la regla del dueño, y la
// búsqueda del asesor por cliente
@QuarkusTest
class AccountQueriesTest extends IntegrationTest {

    UUID anaSavings;
    UUID anaChecking;
    UUID luisSavings;

    @BeforeEach
    void accounts() {
        emptyBank();
        anaSavings = insertAccount(FakeCustomers.ANA, "001-0000000001-6", "PEN", "1500.50", "ACTIVE");
        anaChecking = insertAccount(FakeCustomers.ANA, "001-0000000002-4", "USD", "0", "ACTIVE");
        update("update account set created_at = now() + interval '1 second' where id = ?", anaChecking);
        luisSavings = insertAccount(FakeCustomers.LUIS, "001-0000000003-2", "PEN", "370", "ACTIVE");
    }

    @Test
    void customerSeesOnlyHerAccountsWithExactBalances() {
        get("/accounts/me", Tokens.customer(Tokens.ANA_OID)).statusCode(200)
                .body("$", hasSize(2))
                .body("[0].id", equalTo(anaSavings.toString()))
                .body("[0].balance", equalTo("1500.50"))
                .body("[1].id", equalTo(anaChecking.toString()))
                .body("[1].balance", equalTo("0.00"));
        // accounts reenvía a customers el token del usuario (zero trust): el falso lo lee para saber quién es
        assertThat(AccountsTestResource.CUSTOMERS.lastAuthorization).startsWith("Bearer eyJ");
    }

    @Test
    void customerWithoutALinkedCustomerIs403() {
        get("/accounts/me", Tokens.customer(Tokens.UNLINKED_OID)).statusCode(403)
                .body("title", equalTo("Acceso denegado"));
    }

    @Test
    void customersServiceDownIs503() {
        get("/accounts/me", Tokens.customer(Tokens.CUSTOMERS_DOWN_OID)).statusCode(503)
                .body("title", equalTo("Servicio no disponible"));
    }

    @Test
    void adminHasNoAccountsOfHisOwn() {
        get("/accounts/me", Tokens.admin()).statusCode(403);
    }

    @Test
    void customerSeesHerOwnAccount() {
        get("/accounts/" + anaSavings, Tokens.customer(Tokens.ANA_OID)).statusCode(200)
                .body("customerId", equalTo(FakeCustomers.ANA))
                .body("balance", equalTo("1500.50"));
    }

    @Test
    void foreignAndMissingAccountsGetTheSame403() {
        String foreign = get("/accounts/" + luisSavings, Tokens.customer(Tokens.ANA_OID)).statusCode(403)
                .extract().asString();
        String missing = get("/accounts/" + UUID.randomUUID(), Tokens.customer(Tokens.ANA_OID)).statusCode(403)
                .extract().asString();
        assertThat(foreign.replaceAll("[0-9a-f-]{36}", "{id}")).isEqualTo(missing.replaceAll("[0-9a-f-]{36}", "{id}"));
    }

    @Test
    void adminSeesAnyAccountOr404() {
        get("/accounts/" + luisSavings, Tokens.admin()).statusCode(200).body("customerId", equalTo(FakeCustomers.LUIS));
        get("/accounts/" + UUID.randomUUID(), Tokens.admin()).statusCode(404)
                .body("title", equalTo("Recurso no encontrado"));
    }

    @Test
    void customersServiceDownOnAnAccountIs503() {
        get("/accounts/" + anaSavings, Tokens.customer(Tokens.CUSTOMERS_DOWN_OID)).statusCode(503);
    }

    @Test
    void anIdThatIsNotAUuidIs400() {
        get("/accounts/no-es-uuid", Tokens.admin()).statusCode(400)
                .body("title", equalTo("Datos inválidos"))
                .body("detail", equalTo("Falta un parámetro o tiene un formato inválido"));
    }

    @Test
    void adminListsTheAccountsOfACustomer() {
        get("/accounts?customerId=" + FakeCustomers.ANA, Tokens.admin()).statusCode(200)
                .body("$", hasSize(2))
                .body("[0].number", notNullValue());
        get("/accounts?customerId=" + UUID.randomUUID(), Tokens.admin()).statusCode(200).body("$", hasSize(0));
    }

    @Test
    void customerCannotSearchByCustomer() {
        get("/accounts?customerId=" + FakeCustomers.LUIS, Tokens.customer(Tokens.ANA_OID)).statusCode(403);
    }

    @Test
    void customerIdIsRequiredAndMustBeAUuid() {
        get("/accounts", Tokens.admin()).statusCode(400);
        get("/accounts?customerId=no-es-uuid", Tokens.admin()).statusCode(400)
                .body("detail", equalTo("La solicitud tiene parámetros inválidos"))
                .body("errors[0].field", equalTo("customerId"))
                .body("errors[0].message", equalTo("debe ser un UUID"));
    }

    private static ValidatableResponse get(String path, String token) {
        return given().header("Authorization", token).when().get(path).then();
    }
}
