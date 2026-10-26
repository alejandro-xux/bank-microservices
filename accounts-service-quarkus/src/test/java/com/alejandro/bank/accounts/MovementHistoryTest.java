package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.domain.AccountNumber;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

// Historial paginado, los mismos casos que MovementHistoryIntegrationTest (BANK-57). Mientras las transferencias no
// existan en esta versión (BANK-101), el historial se arma con SQL: las mismas filas que dejarían las transferencias
@QuarkusTest
class MovementHistoryTest extends IntegrationTest {

    @Inject
    EntityManagerFactory entityManagerFactory;

    UUID anaPen;
    UUID luisPen;
    String luisPenNumber = AccountNumber.of(920_002);

    // Cuenta de Ana (desde 0.00): 1 depósito de 50.00 y luego 24 transferencias alternadas con Luis (desde 100.00):
    // 25 movimientos en la de Ana y 24 en la de Luis, un segundo después del otro
    @BeforeEach
    void history() {
        emptyBank();
        anaPen = insertAccount(FakeCustomers.ANA, AccountNumber.of(920_001), "PEN", "0", "ACTIVE");
        luisPen = insertAccount(FakeCustomers.LUIS, luisPenNumber, "PEN", "100.00", "ACTIVE");
        Instant time = Instant.parse("2026-10-26T10:00:00Z");
        movement(anaPen, "CREDIT", "50.00", "50.00", null, time);
        int ana = 5000;
        int luis = 10000;
        for (int i = 0; i < 24; i++) {
            time = time.plusSeconds(1);
            boolean anaPays = i % 2 == 0;
            int amount = anaPays ? 100 : 200;
            ana += anaPays ? -amount : amount;
            luis += anaPays ? amount : -amount;
            UUID transfer = UUID.randomUUID();
            update("insert into transfer (id, idempotency_key, request_hash, requested_by, from_account_id, to_account_id, "
                            + "amount, currency, created_at, status) values (?, ?, 'h', 'prueba', ?, ?, ?, 'PEN', ?, 'COMPLETED')",
                    transfer, UUID.randomUUID(), anaPays ? anaPen : luisPen, anaPays ? luisPen : anaPen,
                    new java.math.BigDecimal(amount).movePointLeft(2), java.sql.Timestamp.from(time));
            movement(anaPen, anaPays ? "DEBIT" : "CREDIT", cents(amount), cents(ana), transfer, time);
            movement(luisPen, anaPays ? "CREDIT" : "DEBIT", cents(amount), cents(luis), transfer, time);
        }
    }

    @Test
    void firstPageIsTheMostRecentWithTotals() {
        movements(Tokens.customer(Tokens.ANA_OID), anaPen, "page=0&size=10").statusCode(200)
                .body("content", hasSize(10))
                .body("page", equalTo(0))
                .body("size", equalTo(10))
                .body("totalElements", equalTo(25))
                .body("totalPages", equalTo(3))
                // La última operación fue Luis → Ana (2.00): saldo final 50 − 12×1 + 12×2 = 62.00
                .body("content[0].type", equalTo("CREDIT"))
                .body("content[0].amount", equalTo("2.00"))
                .body("content[0].balanceAfter", equalTo("62.00"));
    }

    @Test
    void movementsComeNewestFirstAndTheDepositIsTheOldest() {
        List<Map<String, Object>> content = page(anaPen, 0, 50).getList("content");
        List<Instant> dates = content.stream().map(line -> Instant.parse((String) line.get("createdAt"))).toList();
        assertThat(dates).isSortedAccordingTo((a, b) -> b.compareTo(a));
        Map<String, Object> oldest = content.getLast();
        assertThat(oldest.get("type")).isEqualTo("CREDIT");
        assertThat(oldest.get("amount")).isEqualTo("50.00");
        assertThat(oldest.get("counterpartyAccount")).as("un depósito no tiene otra cuenta").isNull();
    }

    @Test
    void transferMovementsShowTheOtherAccountMasked() {
        String masked = "001-******" + luisPenNumber.substring(10);
        List<Map<String, Object>> content = page(anaPen, 0, 2).getList("content");
        assertThat(content.get(0).get("type")).isEqualTo("CREDIT");
        assertThat(content.get(1).get("type")).isEqualTo("DEBIT");
        assertThat(content.get(0).get("counterpartyAccount")).isEqualTo(masked);
        assertThat(content.get(1).get("counterpartyAccount")).isEqualTo(masked);
        assertThat(content.get(0).get("transferId")).isNotNull();
    }

    @Test
    void pagesDoNotOverlapAndCoverEverything() {
        Set<Object> ids = new HashSet<>();
        int seen = 0;
        for (int page = 0; page < 3; page++) {
            for (Map<String, Object> line : page(anaPen, page, 10).<Map<String, Object>>getList("content")) {
                ids.add(line.get("id"));
                seen++;
            }
        }
        assertThat(seen).isEqualTo(25);
        assertThat(ids).hasSize(25);
    }

    @Test
    void pageBeyondTheEndIsEmpty() {
        movements(Tokens.admin(), anaPen, "page=9&size=10").statusCode(200)
                .body("content", hasSize(0))
                .body("totalElements", equalTo(25));
    }

    @Test
    void theNumberOfQueriesDoesNotGrowWithThePageSize() {
        long small = queriesFor(5);
        long large = queriesFor(25);
        // Buscar la cuenta (regla del dueño) + la página + el total. Con N+1 serían 1 + 2 por movimiento
        assertThat(large).isEqualTo(small);
        assertThat(large).isEqualTo(3);
    }

    @Test
    void anotherCustomersAccountAndAMissingOneGetTheSame403() {
        String foreign = movements(Tokens.customer(Tokens.ANA_OID), luisPen, "").statusCode(403).extract().asString();
        String missing = movements(Tokens.customer(Tokens.ANA_OID), UUID.randomUUID(), "").statusCode(403).extract().asString();
        assertThat(foreign.replaceAll("[0-9a-f-]{36}", "{id}")).isEqualTo(missing.replaceAll("[0-9a-f-]{36}", "{id}"));
    }

    @Test
    void adminSeesAnyHistoryOr404() {
        movements(Tokens.admin(), luisPen, "size=1").statusCode(200).body("totalElements", equalTo(24));
        movements(Tokens.admin(), UUID.randomUUID(), "").statusCode(404);
    }

    @Test
    void customersServiceDownIs503() {
        movements(Tokens.customer(Tokens.CUSTOMERS_DOWN_OID), anaPen, "").statusCode(503);
    }

    @Test
    void invalidPageOrSizeIs400() {
        for (String query : List.of("size=51", "size=0", "page=-1", "page=uno")) {
            movements(Tokens.admin(), anaPen, query).statusCode(400).body("title", equalTo("Datos inválidos"));
        }
    }

    private long queriesFor(int size) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        movements(Tokens.admin(), anaPen, "size=" + size).statusCode(200).body("content", hasSize(size));
        return statistics.getPrepareStatementCount();
    }

    private JsonPath page(UUID accountId, int page, int size) {
        return movements(Tokens.admin(), accountId, "page=" + page + "&size=" + size).statusCode(200).extract().jsonPath();
    }

    private static ValidatableResponse movements(String token, UUID accountId, String query) {
        return given().header("Authorization", token)
                .when().get("/accounts/" + accountId + "/movements" + (query.isEmpty() ? "" : "?" + query)).then();
    }

    private void movement(UUID account, String type, String amount, String balanceAfter, UUID transfer, Instant at) {
        update("insert into movement (id, account_id, type, amount, balance_after, transfer_id, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), account, type, new java.math.BigDecimal(amount),
                new java.math.BigDecimal(balanceAfter), transfer, java.sql.Timestamp.from(at));
    }

    private static String cents(int value) {
        return new java.math.BigDecimal(value).movePointLeft(2).toPlainString();
    }
}
