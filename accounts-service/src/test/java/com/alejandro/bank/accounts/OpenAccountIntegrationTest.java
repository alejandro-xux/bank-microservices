package com.alejandro.bank.accounts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpenAccountIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void adminOpensAnAccountWithZeroBalance() throws Exception {
        MvcResult result = mvc.perform(open(ADMIN_TOKEN, ANA, "AHORROS", "PEN"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, matchesPattern("/accounts/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.number").value(matchesPattern("001-\\d{10}-\\d")))
                .andExpect(jsonPath("$.customerId").value(ANA))
                .andExpect(jsonPath("$.type").value("AHORROS"))
                .andExpect(jsonPath("$.currency").value("PEN"))
                .andExpect(jsonPath("$.balance").value("0.00"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        JsonNode body = jsonMapper.readTree(result.getResponse().getContentAsString());
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith(body.get("id").asString());
        assertThat(jdbc.queryForObject("select balance::text from account where id = ?::uuid",
                String.class, body.get("id").asString())).isEqualTo("0.00");
        // El mismo token del asesor llegó a customers-service (token relay)
        assertThat(RECEIVED_AUTHORIZATION.get()).isEqualTo(ADMIN_TOKEN);
    }

    @Test
    void eachAccountGetsADifferentNumber() throws Exception {
        String first = number(mvc.perform(open(ADMIN_TOKEN, ANA, "AHORROS", "PEN")).andReturn());
        String second = number(mvc.perform(open(ADMIN_TOKEN, ANA, "CORRIENTE", "USD")).andReturn());

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void customerCannotOpenAccounts() throws Exception {
        int before = accountsOf(ANA);

        mvc.perform(open(CUSTOMER_TOKEN, ANA, "AHORROS", "PEN"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Acceso denegado"));

        assertThat(accountsOf(ANA)).isEqualTo(before);
        assertThat(RECEIVED_AUTHORIZATION.get()).as("ni siquiera consulta a customers-service").isNull();
    }

    @Test
    void unknownCustomerIs422AndNoAccountIsOpened() throws Exception {
        mvc.perform(open(ADMIN_TOKEN, MISSING, "AHORROS", "PEN"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Cliente no válido"))
                .andExpect(jsonPath("$.detail").value(containsString("no existe")));

        assertThat(accountsOf(MISSING)).isZero();
    }

    @Test
    void blockedCustomerIs422AndNoAccountIsOpened() throws Exception {
        mvc.perform(open(ADMIN_TOKEN, BLOCKED, "AHORROS", "PEN"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("bloqueado")));

        assertThat(accountsOf(BLOCKED)).isZero();
    }

    @Test
    void customersServiceErrorIs503AndNoAccountIsOpened() throws Exception {
        mvc.perform(open(ADMIN_TOKEN, DOWN, "AHORROS", "PEN"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Servicio no disponible"));

        assertThat(accountsOf(DOWN)).isZero();
    }

    @Test
    void customersServiceTooSlowIs503AndNoAccountIsOpened() throws Exception {
        long start = System.currentTimeMillis();

        mvc.perform(open(ADMIN_TOKEN, SLOW, "AHORROS", "PEN"))
                .andExpect(status().isServiceUnavailable());

        // Corta en el read-timeout (2 s), no espera los 3 s del servicio lento
        assertThat(System.currentTimeMillis() - start).isLessThan(2900);
        assertThat(accountsOf(SLOW)).isZero();
    }

    @Test
    void invalidFieldsAre400WithEachError() throws Exception {
        mvc.perform(post("/accounts").header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"no-es-uuid\",\"currency\":\"PEN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Datos inválidos"))
                .andExpect(jsonPath("$.errors[?(@.field == 'customerId')].message").value("debe ser un UUID"))
                .andExpect(jsonPath("$.errors[?(@.field == 'type')].message").value("es obligatorio"));
    }

    @Test
    void unknownCurrencyIs400() throws Exception {
        mvc.perform(open(ADMIN_TOKEN, ANA, "AHORROS", "EUR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud mal formada"));
    }

    private static org.springframework.test.web.servlet.RequestBuilder open(
            String token, String customerId, String type, String currency) {
        return post("/accounts")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + customerId + "\",\"type\":\"" + type + "\",\"currency\":\"" + currency + "\"}");
    }

    private String number(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString()).get("number").asString();
    }

    private int accountsOf(String customerId) {
        return jdbc.queryForObject("select count(*) from account where customer_id = ?", Integer.class, customerId);
    }
}
