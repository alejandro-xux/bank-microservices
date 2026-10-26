package com.alejandro.bank.accounts;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

// accounts contra el binario nativo (BANK-109; testNative, workflow native.yml): lo que en JVM funciona por reflexión
// y en nativo solo si se registró al compilar (JSON de entrada y salida, Protobuf del outbox, el cliente REST del core,
// gRPC a customers) pasa por aquí. Solo HTTP, sin @Inject: @QuarkusIntegrationTest no inyecta nada. El recurso se
// declara en esta clase: desde src/native-test Quarkus no ve los de src/test (BANK-107)
@QuarkusTestResource(AccountsTestResource.class)
@QuarkusIntegrationTest
class AccountsNativeIT {

    @Test
    void openDepositAndTransferAgainstTheBinary() {
        String ana = given().header("Authorization", Tokens.admin()).contentType("application/json")
                .body("{\"customerId\":\"" + FakeCustomers.ANA + "\",\"type\":\"AHORROS\",\"currency\":\"PEN\"}")
                .when().post("/accounts").then()
                .statusCode(201).body("balance", equalTo("0.00")).body("status", equalTo("ACTIVE"))
                .extract().path("id");
        String luisNumber = given().header("Authorization", Tokens.admin()).contentType("application/json")
                .body("{\"customerId\":\"" + FakeCustomers.LUIS + "\",\"type\":\"AHORROS\",\"currency\":\"PEN\"}")
                .when().post("/accounts").then().statusCode(201).extract().path("number");

        given().header("Authorization", Tokens.admin()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").body("{\"amount\":\"100.00\"}")
                .when().post("/accounts/" + ana + "/deposits").then()
                .statusCode(201).body("type", equalTo("CREDIT")).body("balanceAfter", equalTo("100.00"));

        // Transferencia completa: el core emulado la registra y el dinero se mueve
        transfer(ana, luisNumber, "30.00").statusCode(201).body("status", equalTo("COMPLETED"));
        // .13: el core la rechaza (057) y el dinero vuelve
        transfer(ana, luisNumber, "5.13").statusCode(422)
                .body("transferStatus", equalTo("FAILED")).body("reason", equalTo("057"));

        given().header("Authorization", Tokens.customer(Tokens.ANA_OID)).when().get("/accounts/" + ana).then()
                .statusCode(200).body("balance", equalTo("70.00"));
        // El estado de cuenta: el depósito, el débito de la transferencia y el débito y la reversa del .13
        given().header("Authorization", Tokens.customer(Tokens.ANA_OID)).when().get("/accounts/" + ana + "/movements")
                .then().statusCode(200).body("content.size()", equalTo(4));
    }

    @Test
    void errorsAreProblemDetails() {
        given().header("Authorization", Tokens.customer(Tokens.ANA_OID))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").body("{\"fromAccountId\":\"" + UUID.randomUUID() + "\"}")
                .when().post("/transfers").then()
                .statusCode(400)
                .contentType(startsWith("application/problem+json"))
                .body("status", equalTo(400))
                .body("instance", equalTo("/transfers"));
    }

    @Test
    void withoutTokenIs401() {
        given().when().get("/accounts/me").then().statusCode(401);
    }

    private static io.restassured.response.ValidatableResponse transfer(String from, String toNumber, String amount) {
        return given().header("Authorization", Tokens.customer(Tokens.ANA_OID))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .body("{\"fromAccountId\":\"" + from + "\",\"toAccountNumber\":\"" + toNumber + "\",\"amount\":\"" + amount + "\"}")
                .when().post("/transfers").then();
    }
}
