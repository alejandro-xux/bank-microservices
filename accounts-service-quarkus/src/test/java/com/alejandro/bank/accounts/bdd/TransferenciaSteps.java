package com.alejandro.bank.accounts.bdd;

import com.alejandro.bank.accounts.FakeCustomers;
import com.alejandro.bank.accounts.IntegrationTest;
import com.alejandro.bank.accounts.Tokens;
import com.alejandro.bank.accounts.domain.AccountNumber;
import com.alejandro.bank.accounts.service.TransferRecovery;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.ParameterType;
import io.cucumber.java.es.Cuando;
import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import io.cucumber.java.es.Y;
import io.quarkus.arc.Arc;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

// Los pasos de transferencias.feature para la versión Quarkus (BANK-103): las mismas frases y las mismas reglas que
// TransferenciaSteps de accounts-service. Los "Dado" preparan las cuentas en la base; los "Cuando" y "Entonces" pasan
// por la API con REST Assured, contra la aplicación que levantó TransferenciasBddTest. Cucumber crea la clase él
// mismo (sin CDI): los beans se piden a Arc, que es el contenedor de esa misma aplicación
public class TransferenciaSteps {

    private final AgroalDataSource dataSource = Arc.container().instance(AgroalDataSource.class).get();
    private final TransferRecovery recovery = Arc.container().instance(TransferRecovery.class).get();
    private final String anaToken = Tokens.customer(Tokens.ANA_OID);
    private final String luisToken = Tokens.customer(Tokens.LUIS_OID);

    // Lo que hay en el banco al empezar el escenario, para la regla de cada escenario: saldos + pendientes = esto
    private BigDecimal moneyAtStart = BigDecimal.ZERO;

    private UUID anaPen;
    private UUID luisPen;
    private UUID luisUsd;
    private UUID lastKey;
    private Response lastResponse;
    private String lastTransferId;

    @Before
    public void emptyBank() throws Exception {
        update("delete from outbox");
        update("delete from movement");
        update("delete from transfer");
        update("delete from account");
        // El core recuerda los asientos: cada escenario empieza con uno que no recibió nada
        IntegrationTest.forgetCoreBanking();
    }

    @After
    public void theBankNeverCreatesNorLosesMoney() {
        BigDecimal balances = new BigDecimal(query("select coalesce(sum(balance), 0)::text from account"));
        BigDecimal inTransit = new BigDecimal(query("select coalesce(sum(amount), 0)::text from transfer where status = 'PENDING'"));
        assertThat(balances.add(inTransit)).as("saldos + pendientes").isEqualByComparingTo(moneyAtStart);
    }

    // Un monto como lo escribe la API: dígitos, punto y dos decimales ("104.13"). No {bigdecimal}: en un feature en
    // español lo lee con el formato de España, donde el punto separa miles (BANK-87)
    @ParameterType("\\d+\\.\\d{2}")
    public BigDecimal monto(String text) {
        return new BigDecimal(text);
    }

    // --- Dado -----------------------------------------------------------------------------------------------

    @Dado("que Ana tiene una cuenta en soles con S\\/ {monto}")
    public void anaHasAPenAccount(BigDecimal balance) {
        anaPen = insertAccount(FakeCustomers.ANA, 960_001, "PEN", balance);
    }

    @Dado("que Luis tiene una cuenta en soles con S\\/ {monto}")
    public void luisHasAPenAccount(BigDecimal balance) {
        luisPen = insertAccount(FakeCustomers.LUIS, 960_002, "PEN", balance);
    }

    @Dado("que Luis también tiene una cuenta en dólares con US$ {monto}")
    public void luisAlsoHasAUsdAccount(BigDecimal balance) {
        luisUsd = insertAccount(FakeCustomers.LUIS, 960_003, "USD", balance);
    }

    // --- Cuando ---------------------------------------------------------------------------------------------

    @Cuando("Ana transfiere S\\/ {monto} a la cuenta de Luis")
    public void anaTransfersToLuis(BigDecimal amount) {
        transfer(UUID.randomUUID(), luisPen, amount);
    }

    @Cuando("Ana transfiere S\\/ {monto} a la cuenta en dólares de Luis")
    public void anaTransfersToLuisUsd(BigDecimal amount) {
        transfer(UUID.randomUUID(), luisUsd, amount);
    }

    @Cuando("pasa el recuperador")
    public void theRecoveryRuns() {
        // El proceso de fondo que en producción corre cada 5 s; en las pruebas está apagado y se llama aquí
        recovery.recoverPending();
    }

    // --- Entonces -------------------------------------------------------------------------------------------

    @Entonces("la transferencia queda {string}")
    public void theTransferEndsAs(String status) {
        assertThat(transferNow().getString("status")).isEqualTo(status);
    }

    @Entonces("la transferencia queda {string} con el motivo {string}")
    public void theTransferEndsAsWithReason(String status, String reason) {
        JsonPath transfer = transferNow();
        assertThat(transfer.getString("status")).isEqualTo(status);
        assertThat(transfer.getString("failureReason")).isEqualTo(reason);
    }

    @Entonces("la transferencia se rechaza con {int} {string}")
    public void theTransferIsRejected(int status, String title) {
        assertThat(lastResponse.statusCode()).isEqualTo(status);
        assertThat(lastResponse.jsonPath().getString("title")).isEqualTo(title);
    }

    @Y("el motivo dice {string}")
    public void theReasonSays(String text) {
        assertThat(lastResponse.jsonPath().getString("detail")).contains(text);
    }

    @Y("la cuenta de Ana tiene S\\/ {monto}")
    public void anaHas(BigDecimal balance) {
        assertThat(balanceOf(anaPen, anaToken)).isEqualByComparingTo(balance);
    }

    @Y("la cuenta de Luis tiene S\\/ {monto}")
    public void luisHas(BigDecimal balance) {
        assertThat(balanceOf(luisPen, luisToken)).isEqualByComparingTo(balance);
    }

    @Y("la cuenta de Ana tiene los movimientos {string}")
    public void anaHasTheMovements(String types) {
        List<String> found = given().header("Authorization", anaToken).when().get("/accounts/" + anaPen + "/movements")
                .then().extract().jsonPath().getList("content.type", String.class);
        assertThat(found).containsExactlyInAnyOrderElementsOf(Arrays.stream(types.split(",")).map(String::trim).toList());
    }

    @Y("queda el evento {string} para publicar")
    public void theEventIsWaitingToBePublished(String eventType) {
        // El relay está apagado en las pruebas: el evento queda en el outbox, en la misma transacción que el dinero
        assertThat(query("select string_agg(event_type, ', ') from outbox")).isEqualTo(eventType);
    }

    @Y("la misma clave sirve para reintentar con S\\/ {monto}")
    public void theSameKeyWorksForARetry(BigDecimal amount) {
        // Un rechazo por reglas deshace todo, también el reclamo de la clave: el cliente corrige el monto y reintenta
        transfer(lastKey, luisPen, amount);
        assertThat(lastResponse.statusCode()).isEqualTo(201);
    }

    // --- Ayudas ---------------------------------------------------------------------------------------------

    private void transfer(UUID key, UUID toAccountId, BigDecimal amount) {
        lastKey = key;
        String toNumber = query("select number from account where id = ?", toAccountId);
        lastResponse = given().header("Authorization", anaToken).header("Idempotency-Key", key.toString())
                .contentType("application/json")
                .body("{\"fromAccountId\":\"" + anaPen + "\",\"toAccountNumber\":\"" + toNumber
                        + "\",\"amount\":\"" + amount.toPlainString() + "\"}")
                .when().post("/transfers").then().extract().response();
        // 201/202: la transferencia; 422 del core: un ProblemDetail con transferId; 422 por reglas: no hay transferencia
        JsonPath body = lastResponse.jsonPath();
        lastTransferId = body.getString("transferId") != null ? body.getString("transferId") : body.getString("id");
    }

    private JsonPath transferNow() {
        assertThat(lastTransferId).as("la última petición creó una transferencia").isNotNull();
        return given().header("Authorization", anaToken).when().get("/transfers/" + lastTransferId)
                .then().extract().jsonPath();
    }

    private BigDecimal balanceOf(UUID accountId, String token) {
        return new BigDecimal(given().header("Authorization", token).when().get("/accounts/" + accountId)
                .then().extract().jsonPath().getString("balance"));
    }

    private UUID insertAccount(String customerId, int sequence, String currency, BigDecimal balance) {
        UUID id = UUID.randomUUID();
        update("insert into account (id, number, customer_id, type, currency, balance, status, created_at, updated_at) "
                        + "values (?, ?, ?, 'AHORROS', ?, ?, 'ACTIVE', now(), now())",
                id, AccountNumber.of(sequence), customerId, currency, balance);
        moneyAtStart = moneyAtStart.add(balance);
        return id;
    }

    private void update(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = prepare(connection, sql, parameters)) {
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String query(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = prepare(connection, sql, parameters);
             ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
        return statement;
    }
}
