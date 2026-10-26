package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.service.DepositService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

// Depósitos, los mismos casos que DepositIntegrationTest (BANK-55)
@QuarkusTest
class DepositTest extends IntegrationTest {

    @Inject
    DepositService depositService;

    UUID active;
    UUID blocked;

    @BeforeEach
    void accounts() {
        emptyBank();
        active = insertAccount(FakeCustomers.ANA, "001-0000000001-6", "PEN", "1500.50", "ACTIVE");
        blocked = insertAccount(FakeCustomers.ANA, "001-0000000002-4", "PEN", "10.00", "BLOCKED");
    }

    @Test
    void depositAddsToTheBalanceAndRecordsACreditMovement() {
        deposit(active, Tokens.admin(), "{\"amount\":\"150.00\"}").statusCode(201)
                .body("accountId", equalTo(active.toString()))
                .body("type", equalTo("CREDIT"))
                .body("amount", equalTo("150.00"))
                .body("balanceAfter", equalTo("1650.50"))
                .body("transferId", nullValue());
        assertThat(balance(active)).isEqualTo("1650.50");
        assertThat(queryString("select type || ' ' || amount::text || ' ' || balance_after::text || ' ' || coalesce(transfer_id::text, '-') "
                + "from movement where account_id = ?", active)).isEqualTo("CREDIT 150.00 1650.50 -");
    }

    @Test
    void amountAsJsonNumberKeepsItsExactValue() {
        deposit(active, Tokens.admin(), "{\"amount\":0.10}").statusCode(201);
        // 1500.50 + 0.10 + 0.20 exacto (con double, 0.1 + 0.2 = 0.30000000000000004)
        deposit(active, Tokens.admin(), "{\"amount\":0.20}").statusCode(201).body("balanceAfter", equalTo("1500.80"));
    }

    @Test
    void simultaneousDepositsAreNeverLost() throws Exception {
        int deposits = 20;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < deposits; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return depositService.deposit(UUID.randomUUID(), active, new BigDecimal("10.00"));
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get();
            }
        }
        // Sin candado, varios leerían el mismo saldo y se perderían depósitos
        assertThat(balance(active)).isEqualTo("1700.50");
        assertThat(queryString("select count(distinct balance_after)::text from movement where account_id = ?", active))
                .isEqualTo(String.valueOf(deposits));
        assertThat(queryString("select max(balance_after)::text from movement where account_id = ?", active))
                .isEqualTo("1700.50");
    }

    @Test
    void movementsAlwaysAddUpToTheBalance() {
        for (String amount : List.of("100.00", "0.01", "2500.99")) {
            deposit(active, Tokens.admin(), "{\"amount\":\"" + amount + "\"}").statusCode(201);
        }
        BigDecimal credited = new BigDecimal(queryString("select sum(amount)::text from movement where account_id = ?", active));
        assertThat(new BigDecimal("1500.50").add(credited)).isEqualByComparingTo(balance(active));
    }

    @Test
    void invalidAmountsAre400AndChangeNothing() {
        for (String body : List.of("{}", "{\"amount\":0}", "{\"amount\":\"-5.00\"}", "{\"amount\":\"10.555\"}",
                "{\"amount\":\"1234567890123456.00\"}")) {
            deposit(active, Tokens.admin(), body).statusCode(400)
                    .contentType("application/problem+json")
                    .body("errors[0].field", equalTo("amount"));
        }
        deposit(active, Tokens.admin(), "{\"amount\":\"diez\"}").statusCode(400);
        assertThat(balance(active)).isEqualTo("1500.50");
        assertThat(movements()).isZero();
    }

    @Test
    void unknownAccountIs404() {
        deposit(UUID.randomUUID(), Tokens.admin(), "{\"amount\":\"10.00\"}").statusCode(404);
        assertThat(movements()).isZero();
    }

    @Test
    void blockedAccountIs422AndChangesNothing() {
        deposit(blocked, Tokens.admin(), "{\"amount\":\"10.00\"}").statusCode(422)
                .body("title", equalTo("Cuenta no activa"));
        assertThat(balance(blocked)).isEqualTo("10.00");
        assertThat(movements()).isZero();
    }

    @Test
    void customerCannotDeposit() {
        deposit(active, Tokens.customer(Tokens.ANA_OID), "{\"amount\":\"10.00\"}").statusCode(403);
        assertThat(balance(active)).isEqualTo("1500.50");
    }

    // Cada llamada es un depósito distinto (clave nueva)
    private static ValidatableResponse deposit(UUID accountId, String token, String body) {
        return given().header("Authorization", token).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").body(body)
                .when().post("/accounts/" + accountId + "/deposits").then();
    }
}
