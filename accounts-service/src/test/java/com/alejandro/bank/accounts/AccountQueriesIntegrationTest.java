package com.alejandro.bank.accounts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BANK-54: mis cuentas, una cuenta con la regla del dueño, y la búsqueda del asesor
class AccountQueriesIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    UUID anaSavings;
    UUID anaChecking;
    UUID luisSavings;

    // Siempre las mismas 3 cuentas: 2 de Ana y 1 de Luis
    @BeforeEach
    void accounts() {
        jdbc.update("delete from movement");
        jdbc.update("delete from transfer");
        jdbc.update("delete from account");
        anaSavings = insertAccount(ANA, "AHORROS", "PEN", "1500.50", Instant.parse("2026-10-01T10:00:00Z"));
        anaChecking = insertAccount(ANA, "CORRIENTE", "USD", "0.00", Instant.parse("2026-10-02T10:00:00Z"));
        luisSavings = insertAccount(LUIS, "AHORROS", "PEN", "99.99", Instant.parse("2026-10-03T10:00:00Z"));
    }

    // ---- GET /accounts/me ----

    @Test
    void customerSeesOnlyHerAccountsWithExactBalances() throws Exception {
        mvc.perform(get("/accounts/me").header(HttpHeaders.AUTHORIZATION, CUSTOMER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(anaSavings.toString()))
                .andExpect(jsonPath("$[0].balance").value("1500.50"))
                .andExpect(jsonPath("$[1].id").value(anaChecking.toString()))
                .andExpect(jsonPath("$[1].balance").value("0.00"));

        // Supo quién es Ana preguntando a customers-service con el mismo token
        assertThat(RECEIVED_AUTHORIZATION.get()).isEqualTo(CUSTOMER_TOKEN);
    }

    @Test
    void customerWithoutLinkedCustomerIs403() throws Exception {
        mvc.perform(get("/accounts/me").header(HttpHeaders.AUTHORIZATION, UNLINKED_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Acceso denegado"));
    }

    @Test
    void customersServiceDownIs503() throws Exception {
        mvc.perform(get("/accounts/me").header(HttpHeaders.AUTHORIZATION, CUSTOMERS_DOWN_TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Servicio no disponible"));
    }

    @Test
    void adminHasNoAccountsOfHisOwn() throws Exception {
        mvc.perform(get("/accounts/me").header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN))
                .andExpect(status().isForbidden());

        assertThat(RECEIVED_AUTHORIZATION.get()).as("ni siquiera consulta a customers-service").isNull();
    }

    // ---- GET /accounts/{id} ----

    @Test
    void customerSeesHerOwnAccount() throws Exception {
        mvc.perform(get("/accounts/" + anaSavings).header(HttpHeaders.AUTHORIZATION, CUSTOMER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(ANA))
                .andExpect(jsonPath("$.balance").value("1500.50"));
    }

    @Test
    void anotherCustomersAccountAndAMissingOneGetTheSame403() throws Exception {
        String foreign = mvc.perform(get("/accounts/" + luisSavings).header(HttpHeaders.AUTHORIZATION, CUSTOMER_TOKEN))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(get("/accounts/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, CUSTOMER_TOKEN))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        // Mismo cuerpo salvo la ruta: no se puede saber si la cuenta existe
        assertThat(foreign.replace(luisSavings.toString(), "{id}"))
                .isEqualTo(missing.replaceAll("[0-9a-f-]{36}", "{id}"));
    }

    @Test
    void adminSeesAnyAccountOr404() throws Exception {
        mvc.perform(get("/accounts/" + luisSavings).header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(LUIS));
        mvc.perform(get("/accounts/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"));

        assertThat(RECEIVED_AUTHORIZATION.get()).as("el asesor no necesita a customers-service").isNull();
    }

    @Test
    void customerAccountWithCustomersServiceDownIs503() throws Exception {
        mvc.perform(get("/accounts/" + anaSavings).header(HttpHeaders.AUTHORIZATION, CUSTOMERS_DOWN_TOKEN))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void accountIdThatIsNotAUuidIs400() throws Exception {
        mvc.perform(get("/accounts/no-es-uuid").header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ---- GET /accounts?customerId= ----

    @Test
    void adminListsTheAccountsOfACustomer() throws Exception {
        mvc.perform(get("/accounts").param("customerId", ANA).header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].number").exists());
        mvc.perform(get("/accounts").param("customerId", UUID.randomUUID().toString())
                        .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void customerCannotSearchByCustomer() throws Exception {
        mvc.perform(get("/accounts").param("customerId", LUIS).header(HttpHeaders.AUTHORIZATION, CUSTOMER_TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void searchWithoutOrWithInvalidCustomerIdIs400() throws Exception {
        mvc.perform(get("/accounts").header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/accounts").param("customerId", "no-es-uuid").header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("customerId"))
                .andExpect(jsonPath("$.errors[0].message").value("debe ser un UUID"));
    }

    private UUID insertAccount(String customerId, String type, String currency, String balance, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into account (id, number, customer_id, type, currency, balance, status, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                id, "001-" + id.toString().substring(0, 10), customerId, type, currency, new BigDecimal(balance),
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt));
        return id;
    }
}
