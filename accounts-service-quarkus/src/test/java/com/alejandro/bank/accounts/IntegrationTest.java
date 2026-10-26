package com.alejandro.bank.accounts;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

// Base de las pruebas de integración (como PostgresIntegrationTest de accounts-service): PostgreSQL, customers falso
// y tokens firmados de AccountsTestResource, y utilidades para preparar y mirar la base con SQL directo
@QuarkusTestResource(AccountsTestResource.class)
public abstract class IntegrationTest {

    @Inject
    protected AgroalDataSource dataSource;

    protected void emptyBank() {
        update("delete from outbox");
        update("delete from movement");
        update("delete from transfer");
        update("delete from account");
    }

    protected UUID insertAccount(String customerId, String number, String currency, String balance, String status) {
        UUID id = UUID.randomUUID();
        update("insert into account (id, number, customer_id, type, currency, balance, status, created_at, updated_at) "
                + "values (?, ?, ?, 'AHORROS', ?, ?, ?, now(), now())", id, number, customerId, currency,
                new BigDecimal(balance), status);
        return id;
    }

    protected String balance(UUID accountId) {
        return queryString("select balance::text from account where id = ?", accountId);
    }

    protected int movements() {
        return Integer.parseInt(queryString("select count(*)::text from movement"));
    }

    protected int update(String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = prepare(connection, sql, parameters)) {
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    protected String queryString(String sql, Object... parameters) {
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
