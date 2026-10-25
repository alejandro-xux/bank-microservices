package com.alejandro.bank.accounts.bdd;

import com.alejandro.bank.accounts.domain.AccountNumber;
import com.alejandro.bank.accounts.service.TransferRecovery;
import com.jayway.jsonpath.JsonPath;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.ParameterType;
import io.cucumber.java.es.Cuando;
import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import io.cucumber.java.es.Y;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.alejandro.bank.accounts.bdd.BddSpringConfiguration.ANA_ID;
import static com.alejandro.bank.accounts.bdd.BddSpringConfiguration.ANA_TOKEN;
import static com.alejandro.bank.accounts.bdd.BddSpringConfiguration.LUIS_BEARER;
import static com.alejandro.bank.accounts.bdd.BddSpringConfiguration.LUIS_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// Los pasos de transferencias.feature (BANK-87). Los "Dado" preparan las cuentas en la base (como las demás pruebas
// de integración); los "Cuando" y "Entonces" pasan por la API, igual que un cliente. Cucumber crea una instancia
// nueva por escenario: los campos no se arrastran de uno a otro
public class TransferenciaSteps {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransferRecovery recovery;

    // El JwtDecoder simulado de la base (@MockitoBean): Spring lo reinicia después de cada escenario
    @Autowired
    JwtDecoder jwtDecoder;

    // Lo que hay en el banco al empezar el escenario, para la regla de cada escenario: saldos + pendientes = esto
    private BigDecimal moneyAtStart = BigDecimal.ZERO;

    private UUID anaPen;
    private UUID luisPen;
    private UUID luisUsd;
    private UUID lastKey;
    private MockHttpServletResponse lastResponse;
    private String lastTransferId;

    @Before
    public void emptyBank() {
        BddSpringConfiguration.prepareTokens(jwtDecoder);
        jdbc.update("delete from outbox");
        jdbc.update("delete from movement");
        jdbc.update("delete from transfer");
        jdbc.update("delete from account");
    }

    @After
    public void theBankNeverCreatesNorLosesMoney() {
        BigDecimal balances = jdbc.queryForObject("select coalesce(sum(balance), 0) from account", BigDecimal.class);
        BigDecimal inTransit = jdbc.queryForObject("select coalesce(sum(amount), 0) from transfer where status = 'PENDING'", BigDecimal.class);
        assertThat(balances.add(inTransit)).as("saldos + pendientes").isEqualByComparingTo(moneyAtStart);
    }

    // Un monto como lo escribe la API: dígitos, punto y dos decimales ("104.13"). No se usa {bigdecimal}: en un
    // feature en español lo lee con el formato de España, donde el punto separa miles, y "104.13" llegaba como 10413.
    // Los escenarios pasaban igual (todo multiplicado por 100) y las reglas del mainframe por céntimos no se aplicaban
    @ParameterType("\\d+\\.\\d{2}")
    public BigDecimal monto(String text) {
        return new BigDecimal(text);
    }

    // --- Dado -----------------------------------------------------------------------------------------------

    @Dado("que Ana tiene una cuenta en soles con S\\/ {monto}")
    public void anaHasAPenAccount(BigDecimal balance) {
        anaPen = insertAccount(ANA_ID, 960_001, "PEN", balance);
    }

    @Dado("que Luis tiene una cuenta en soles con S\\/ {monto}")
    public void luisHasAPenAccount(BigDecimal balance) {
        luisPen = insertAccount(LUIS_ID, 960_002, "PEN", balance);
    }

    @Dado("que Luis también tiene una cuenta en dólares con US$ {monto}")
    public void luisAlsoHasAUsdAccount(BigDecimal balance) {
        luisUsd = insertAccount(LUIS_ID, 960_003, "USD", balance);
    }

    // --- Cuando ---------------------------------------------------------------------------------------------

    @Cuando("Ana transfiere S\\/ {monto} a la cuenta de Luis")
    public void anaTransfersToLuis(BigDecimal amount) throws Exception {
        transfer(UUID.randomUUID(), luisPen, amount);
    }

    @Cuando("Ana transfiere S\\/ {monto} a la cuenta en dólares de Luis")
    public void anaTransfersToLuisUsd(BigDecimal amount) throws Exception {
        transfer(UUID.randomUUID(), luisUsd, amount);
    }

    @Cuando("pasa el recuperador")
    public void theRecoveryRuns() {
        // El proceso de fondo que en producción corre cada 5 s (BANK-79); en las pruebas está apagado y se llama aquí
        recovery.recoverPending();
    }

    // --- Entonces -------------------------------------------------------------------------------------------

    @Entonces("la transferencia queda {string}")
    public void theTransferEndsAs(String status) throws Exception {
        assertThat(JsonPath.<String>read(transferNow(), "$.status")).isEqualTo(status);
    }

    @Entonces("la transferencia queda {string} con el motivo {string}")
    public void theTransferEndsAsWithReason(String status, String reason) throws Exception {
        String transfer = transferNow();
        assertThat(JsonPath.<String>read(transfer, "$.status")).isEqualTo(status);
        assertThat(JsonPath.<String>read(transfer, "$.failureReason")).isEqualTo(reason);
    }

    @Entonces("la transferencia se rechaza con {int} {string}")
    public void theTransferIsRejected(int status, String title) throws Exception {
        assertThat(lastResponse.getStatus()).isEqualTo(status);
        assertThat(JsonPath.<String>read(lastResponse.getContentAsString(), "$.title")).isEqualTo(title);
    }

    @Y("el motivo dice {string}")
    public void theReasonSays(String text) throws Exception {
        assertThat(JsonPath.<String>read(lastResponse.getContentAsString(), "$.detail")).contains(text);
    }

    @Y("la cuenta de Ana tiene S\\/ {monto}")
    public void anaHas(BigDecimal balance) throws Exception {
        assertThat(balanceOf(anaPen, ANA_TOKEN)).isEqualByComparingTo(balance);
    }

    @Y("la cuenta de Luis tiene S\\/ {monto}")
    public void luisHas(BigDecimal balance) throws Exception {
        assertThat(balanceOf(luisPen, LUIS_BEARER)).isEqualByComparingTo(balance);
    }

    @Y("la cuenta de Ana tiene los movimientos {string}")
    public void anaHasTheMovements(String types) throws Exception {
        String page = mvc.perform(get("/accounts/" + anaPen + "/movements").header(HttpHeaders.AUTHORIZATION, ANA_TOKEN))
                .andReturn().getResponse().getContentAsString();
        List<String> found = JsonPath.read(page, "$.content[*].type");
        assertThat(found).containsExactlyInAnyOrderElementsOf(Arrays.stream(types.split(",")).map(String::trim).toList());
    }

    @Y("queda el evento {string} para publicar")
    public void theEventIsWaitingToBePublished(String eventType) {
        // El relay está apagado en las pruebas: el evento queda en el outbox, en la misma transacción que el dinero
        assertThat(jdbc.queryForList("select event_type from outbox", String.class)).containsExactly(eventType);
    }

    @Y("la misma clave sirve para reintentar con S\\/ {monto}")
    public void theSameKeyWorksForARetry(BigDecimal amount) throws Exception {
        // Un rechazo por reglas deshace todo, también el reclamo de la clave: el cliente corrige el monto y reintenta
        transfer(lastKey, luisPen, amount);
        assertThat(lastResponse.getStatus()).isEqualTo(201);
    }

    // --- Ayudas ---------------------------------------------------------------------------------------------

    private void transfer(UUID key, UUID toAccountId, BigDecimal amount) throws Exception {
        lastKey = key;
        String toNumber = jdbc.queryForObject("select number from account where id = ?", String.class, toAccountId);
        lastResponse = mvc.perform(post("/transfers")
                        .header(HttpHeaders.AUTHORIZATION, ANA_TOKEN)
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":\"" + anaPen + "\",\"toAccountNumber\":\"" + toNumber
                                + "\",\"amount\":\"" + amount.toPlainString() + "\"}"))
                .andReturn().getResponse();
        String body = lastResponse.getContentAsString();
        // 201/202: la transferencia; 422 del core: un ProblemDetail con transferId; 422 por reglas: no hay transferencia
        lastTransferId = body.contains("\"transferId\"") ? JsonPath.read(body, "$.transferId")
                : body.contains("\"id\"") ? JsonPath.read(body, "$.id") : null;
    }

    private String transferNow() throws Exception {
        assertThat(lastTransferId).as("la última petición creó una transferencia").isNotNull();
        return mvc.perform(get("/transfers/" + lastTransferId).header(HttpHeaders.AUTHORIZATION, ANA_TOKEN))
                .andReturn().getResponse().getContentAsString();
    }

    private BigDecimal balanceOf(UUID accountId, String token) throws Exception {
        String account = mvc.perform(get("/accounts/" + accountId).header(HttpHeaders.AUTHORIZATION, token))
                .andReturn().getResponse().getContentAsString();
        return new BigDecimal(JsonPath.<String>read(account, "$.balance"));
    }

    private UUID insertAccount(String customerId, int sequence, String currency, BigDecimal balance) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into account (id, number, customer_id, type, currency, balance, status, created_at, updated_at) "
                        + "values (?, ?, ?, 'AHORROS', ?, ?, 'ACTIVE', now(), now())",
                id, AccountNumber.of(sequence), customerId, currency, balance);
        moneyAtStart = moneyAtStart.add(balance);
        return id;
    }
}
