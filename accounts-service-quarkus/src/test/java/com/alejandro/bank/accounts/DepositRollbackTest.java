package com.alejandro.bank.accounts;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

// Todo o nada (como DepositRollbackIntegrationTest, BANK-55): si el movimiento no se puede guardar, el saldo vuelve
// atrás. En vez de un spy de Mockito, la base rechaza ese movimiento con un trigger que solo existe en esta prueba
@QuarkusTest
class DepositRollbackTest extends IntegrationTest {

    UUID active;

    @BeforeEach
    void accountAndFailingMovement() {
        emptyBank();
        active = insertAccount(FakeCustomers.ANA, "001-0000000001-6", "PEN", "100.00", "ACTIVE");
        update("create or replace function rechazar_movimiento() returns trigger as $$ "
                + "begin raise exception 'movimiento rechazado por la prueba'; end $$ language plpgsql");
        update("create trigger rechazar_movimiento before insert on movement for each row "
                + "when (new.amount = 77.77) execute function rechazar_movimiento()");
    }

    @AfterEach
    void removeTrigger() {
        update("drop trigger if exists rechazar_movimiento on movement");
    }

    @Test
    void ifTheMovementFailsTheBalanceIsRolledBack() {
        given().header("Authorization", Tokens.admin()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").body("{\"amount\":\"77.77\"}")
                .when().post("/accounts/" + active + "/deposits").then().statusCode(500);
        assertThat(balance(active)).isEqualTo("100.00");
        assertThat(movements()).isZero();
    }
}
