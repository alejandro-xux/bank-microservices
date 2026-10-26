package com.alejandro.bank.accounts;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

// GET /accounts/me de punta a punta del esqueleto: el rol, customers por gRPC y la base. Solo las cuentas del cliente
// vinculado, de la más antigua a la más nueva, con el saldo como texto
@QuarkusTest
@QuarkusTestResource(AccountsTestResource.class)
class MyAccountsTest {

    @Inject
    AgroalDataSource dataSource;

    @BeforeEach
    void accounts() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("delete from account");
            statement.executeUpdate("insert into account (id, number, customer_id, type, currency, balance, status, created_at, updated_at) values "
                    + "('11111111-1111-4111-8111-111111111111', '001-0000000002-4', '" + FakeCustomers.ANA + "', 'AHORROS', 'USD', 250, 'ACTIVE', '2026-10-25T10:00:01Z', now()),"
                    + "('22222222-2222-4222-8222-222222222222', '001-0000000001-6', '" + FakeCustomers.ANA + "', 'AHORROS', 'PEN', 1580.5, 'ACTIVE', '2026-10-25T10:00:00Z', now()),"
                    + "('33333333-3333-4333-8333-333333333333', '001-0000000003-2', 'otro-cliente', 'AHORROS', 'PEN', 370, 'ACTIVE', now(), now())");
        }
    }

    @Test
    @TestSecurity(user = "ana", roles = "CUSTOMER")
    @OidcSecurity
    void onlyOwnAccountsOldestFirst() {
        given().when().get("/accounts/me").then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("number", contains("001-0000000001-6", "001-0000000002-4"))
                .body("[0].balance", equalTo("1580.50"))
                .body("[0].customerId", equalTo(FakeCustomers.ANA))
                .body("[0].createdAt", equalTo("2026-10-25T10:00:00Z"));
    }
}
