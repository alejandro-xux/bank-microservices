package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.domain.AccountNumber;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Si algo falla a mitad de T1 (aquí, al guardar el movimiento de débito, cuando el transfer ya se insertó), no queda
// nada: ni el débito ni el registro transfer, y la clave sigue libre para reintentar (como
// TransferRollbackIntegrationTest de accounts-service). La falla la provoca un trigger, como en DepositRollbackTest
@QuarkusTest
class TransferRollbackTest extends IntegrationTest {

    @AfterEach
    void removeTrigger() {
        update("drop trigger if exists rechazar_movimiento on movement");
    }

    @Test
    void aFailureHalfwayRollsBackEverythingAndFreesTheKey() {
        emptyBank();
        UUID from = insertAccount(FakeCustomers.ANA, AccountNumber.of(910_001), "PEN", "1000.00", "ACTIVE");
        UUID to = insertAccount(FakeCustomers.LUIS, AccountNumber.of(910_002), "PEN", "1000.00", "ACTIVE");
        update("create or replace function rechazar_movimiento() returns trigger as $$ "
                + "begin raise exception 'movimiento rechazado por la prueba'; end $$ language plpgsql");
        update("create trigger rechazar_movimiento before insert on movement for each row "
                + "execute function rechazar_movimiento()");
        UUID key = UUID.randomUUID();

        TransferTest.transfer(Tokens.admin(), key, from, AccountNumber.of(910_002), "100.00").statusCode(500);

        assertThat(balance(from)).isEqualTo("1000.00");
        assertThat(balance(to)).isEqualTo("1000.00");
        assertThat(queryString("select count(*)::text from transfer")).isEqualTo("0");

        // La clave no quedó usada: sin el trigger, el reintento se hace
        update("drop trigger rechazar_movimiento on movement");
        TransferTest.transfer(Tokens.admin(), key, from, AccountNumber.of(910_002), "100.00").statusCode(201);
        assertThat(balance(from)).isEqualTo("900.00");
        assertThat(balance(to)).isEqualTo("1100.00");
    }
}
