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

// Depósitos idempotentes, los mismos casos que DepositIdempotencyIntegrationTest (BANK-64)
@QuarkusTest
class DepositIdempotencyTest extends IntegrationTest {

    @Inject
    DepositService depositService;

    UUID active;
    UUID other;
    UUID blocked;

    @BeforeEach
    void accounts() {
        emptyBank();
        active = insertAccount(FakeCustomers.ANA, "001-0000000001-6", "PEN", "0", "ACTIVE");
        other = insertAccount(FakeCustomers.ANA, "001-0000000002-4", "PEN", "0", "ACTIVE");
        blocked = insertAccount(FakeCustomers.ANA, "001-0000000003-2", "PEN", "0", "BLOCKED");
    }

    @Test
    void retryWithTheSameKeyReturnsTheSameMovementWithoutAddingAgain() {
        UUID key = UUID.randomUUID();
        String first = deposit(active, key, "150.00").statusCode(201)
                .header("Idempotent-Replayed", nullValue()).extract().asString();
        String retry = deposit(active, key, "150").statusCode(201)
                .header("Idempotent-Replayed", equalTo("true")).extract().asString();
        assertThat(retry).isEqualTo(first);
        assertThat(balance(active)).isEqualTo("150.00");
        assertThat(movements()).isEqualTo(1);
    }

    @Test
    void sameKeyWithAnotherAmountOrAccountIs409() {
        UUID key = UUID.randomUUID();
        deposit(active, key, "150.00").statusCode(201);
        deposit(active, key, "900.00").statusCode(409).body("title", equalTo("Idempotency-Key en conflicto"));
        deposit(other, key, "150.00").statusCode(409);
        assertThat(balance(active)).isEqualTo("150.00");
        assertThat(balance(other)).isEqualTo("0.00");
        assertThat(movements()).isEqualTo(1);
    }

    @Test
    void doubleClickWithTheSameKeyAddsOnce() throws Exception {
        UUID key = UUID.randomUUID();
        int clicks = 5;
        CountDownLatch start = new CountDownLatch(1);
        List<DepositService.Result> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<DepositService.Result>> futures = new ArrayList<>();
            for (int i = 0; i < clicks; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return depositService.deposit(key, active, new BigDecimal("100.00"));
                }));
            }
            start.countDown();
            for (Future<DepositService.Result> future : futures) {
                results.add(future.get());
            }
        }
        assertThat(balance(active)).isEqualTo("100.00");
        assertThat(movements()).isEqualTo(1);
        assertThat(results).extracting(result -> result.movement().getId()).containsOnly(results.getFirst().movement().getId());
        assertThat(results).filteredOn(DepositService.Result::replayed).hasSize(clicks - 1);
    }

    @Test
    void aRejectedDepositDoesNotUseUpTheKey() {
        UUID key = UUID.randomUUID();
        deposit(blocked, key, "10.00").statusCode(422);
        update("update account set status = 'ACTIVE' where id = ?", blocked);
        deposit(blocked, key, "10.00").statusCode(201).header("Idempotent-Replayed", nullValue());
        assertThat(balance(blocked)).isEqualTo("10.00");
    }

    @Test
    void anUnknownAccountDoesNotUseUpTheKeyEither() {
        UUID key = UUID.randomUUID();
        deposit(UUID.randomUUID(), key, "10.00").statusCode(404);
        deposit(active, key, "10.00").statusCode(201);
    }

    @Test
    void idempotencyKeyIsRequiredAndMustBeAUuid() {
        given().header("Authorization", Tokens.admin()).contentType("application/json").body("{\"amount\":\"10.00\"}")
                .when().post("/accounts/" + active + "/deposits").then().statusCode(400)
                .body("detail", equalTo("Falta un parámetro o tiene un formato inválido"));
        given().header("Authorization", Tokens.admin()).header("Idempotency-Key", "abc").contentType("application/json")
                .body("{\"amount\":\"10.00\"}").when().post("/accounts/" + active + "/deposits").then().statusCode(400);
        assertThat(movements()).isZero();
    }

    private static ValidatableResponse deposit(UUID accountId, UUID key, String amount) {
        return given().header("Authorization", Tokens.admin()).header("Idempotency-Key", key.toString())
                .contentType("application/json").body("{\"amount\":\"" + amount + "\"}")
                .when().post("/accounts/" + accountId + "/deposits").then();
    }
}
