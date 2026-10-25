package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.domain.AccountNumber;
import com.alejandro.bank.accounts.service.DepositService;
import com.alejandro.bank.accounts.service.TransferService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BANK-57: historial paginado con PostgreSQL real, armado con depósitos y transferencias de verdad
class MovementHistoryIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    DepositService depositService;

    @Autowired
    TransferService transferService;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    UUID anaPen;
    UUID luisPen;
    String luisPenNumber = AccountNumber.of(920_002);

    // Cuenta de Ana (desde 0.00): 1 depósito y luego 24 transferencias alternadas con Luis (desde 100.00)
    // = 25 movimientos en la de Ana y 24 en la de Luis
    @BeforeEach
    void history() {
        jdbc.update("delete from movement");
        jdbc.update("delete from transfer");
        jdbc.update("delete from account");
        anaPen = insertAccount(ANA, AccountNumber.of(920_001), "0.00");
        luisPen = insertAccount(LUIS, luisPenNumber, "100.00");
        depositService.deposit(anaPen, new BigDecimal("50.00"));
        for (int i = 0; i < 24; i++) {
            if (i % 2 == 0) {
                transferService.transfer(UUID.randomUUID(), anaPen, luisPenNumber, new BigDecimal("1.00"), admin());
            } else {
                transferService.transfer(UUID.randomUUID(), luisPen, AccountNumber.of(920_001), new BigDecimal("2.00"), admin());
            }
        }
    }

    @Test
    void firstPageIsTheMostRecentWithTotals() throws Exception {
        movements(CUSTOMER_TOKEN, anaPen, "page=0&size=10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(10)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3))
                // La última operación fue Luis → Ana (2.00): saldo final 50 − 12×1 + 12×2 = 62.00
                .andExpect(jsonPath("$.content[0].type").value("CREDIT"))
                .andExpect(jsonPath("$.content[0].amount").value("2.00"))
                .andExpect(jsonPath("$.content[0].balanceAfter").value("62.00"));
    }

    @Test
    void movementsComeNewestFirstAndTheDepositIsTheOldest() throws Exception {
        JsonNode content = page(anaPen, 0, 50).get("content");

        List<Instant> dates = new ArrayList<>();
        content.forEach(line -> dates.add(Instant.parse(line.get("createdAt").asString())));
        assertThat(dates).isSortedAccordingTo((a, b) -> b.compareTo(a));
        JsonNode oldest = content.get(content.size() - 1);
        assertThat(oldest.get("type").asString()).isEqualTo("CREDIT");
        assertThat(oldest.get("amount").asString()).isEqualTo("50.00");
        assertThat(oldest.get("counterpartyAccount").isNull()).as("un depósito no tiene otra cuenta").isTrue();
    }

    @Test
    void transferMovementsShowTheOtherAccountMasked() throws Exception {
        String masked = "001-******" + luisPenNumber.substring(10);

        JsonNode content = page(anaPen, 0, 2).get("content");
        // Las dos más recientes: Luis → Ana (CREDIT) y Ana → Luis (DEBIT); en las dos, la otra es la de Luis
        assertThat(content.get(0).get("type").asString()).isEqualTo("CREDIT");
        assertThat(content.get(1).get("type").asString()).isEqualTo("DEBIT");
        assertThat(content.get(0).get("counterpartyAccount").asString()).isEqualTo(masked);
        assertThat(content.get(1).get("counterpartyAccount").asString()).isEqualTo(masked);
        assertThat(content.get(0).get("transferId").isNull()).isFalse();
    }

    @Test
    void pagesDoNotOverlapAndCoverEverything() throws Exception {
        Set<String> ids = new HashSet<>();
        int seen = 0;
        for (int page = 0; page < 3; page++) {
            for (JsonNode line : page(anaPen, page, 10).get("content")) {
                ids.add(line.get("id").asString());
                seen++;
            }
        }

        assertThat(seen).isEqualTo(25);
        assertThat(ids).hasSize(25);
    }

    @Test
    void pageBeyondTheEndIsEmpty() throws Exception {
        movements(ADMIN_TOKEN, anaPen, "page=9&size=10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(25));
    }

    // ---- sin N+1 ----

    @Test
    void theNumberOfQueriesDoesNotGrowWithThePageSize() throws Exception {
        long small = queriesFor(5);
        long large = queriesFor(25);

        // Buscar la cuenta (regla del dueño) + la página + el total. Con N+1 serían 1 + 2 por movimiento
        assertThat(large).isEqualTo(small);
        assertThat(large).isEqualTo(3);
    }

    // ---- quién puede ----

    @Test
    void anotherCustomersAccountAndAMissingOneGetTheSame403() throws Exception {
        String foreign = movements(CUSTOMER_TOKEN, luisPen, "").andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        String missing = movements(CUSTOMER_TOKEN, UUID.randomUUID(), "").andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertThat(foreign.replaceAll("[0-9a-f-]{36}", "{id}")).isEqualTo(missing.replaceAll("[0-9a-f-]{36}", "{id}"));
    }

    @Test
    void adminSeesAnyHistoryOr404() throws Exception {
        movements(ADMIN_TOKEN, luisPen, "size=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(24));
        movements(ADMIN_TOKEN, UUID.randomUUID(), "").andExpect(status().isNotFound());
    }

    @Test
    void customersServiceDownIs503() throws Exception {
        movements(CUSTOMERS_DOWN_TOKEN, anaPen, "").andExpect(status().isServiceUnavailable());
    }

    @Test
    void invalidPageOrSizeIs400() throws Exception {
        for (String query : List.of("size=51", "size=0", "page=-1")) {
            movements(ADMIN_TOKEN, anaPen, query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Datos inválidos"));
        }
    }

    // ---- utilidades ----

    private long queriesFor(int size) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        movements(ADMIN_TOKEN, anaPen, "size=" + size).andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(size)));
        return statistics.getPrepareStatementCount();
    }

    private ResultActions movements(String token, UUID accountId, String query) throws Exception {
        return mvc.perform(get("/accounts/" + accountId + "/movements" + (query.isEmpty() ? "" : "?" + query))
                .header(HttpHeaders.AUTHORIZATION, token));
    }

    private JsonNode page(UUID accountId, int page, int size) throws Exception {
        return jsonMapper.readTree(movements(ADMIN_TOKEN, accountId, "page=" + page + "&size=" + size)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private UUID insertAccount(String customerId, String number, String balance) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into account (id, number, customer_id, type, currency, balance, status, created_at, updated_at) "
                + "values (?, ?, ?, 'AHORROS', 'PEN', ?, 'ACTIVE', now(), now())", id, number, customerId, new BigDecimal(balance));
        return id;
    }

    private static JwtAuthenticationToken admin() {
        Jwt jwt = Jwt.withTokenValue("token-asesor").header("alg", "RS256").subject("7b2e9d4c").claim("oid", "7b2e9d4c")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        return new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
    }
}
