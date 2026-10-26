package com.alejandro.bank.accounts.bdd;

import com.alejandro.bank.accounts.IntegrationTest;
import io.cucumber.core.cli.Main;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Corre los escenarios de transferencias.feature (el MISMO archivo que accounts-service, build.gradle) contra la
// versión Quarkus (BANK-103). El runtime de Cucumber se lanza dentro de un @QuarkusTest: la aplicación ya está andando
// en esta misma JVM, con PostgreSQL, Kafka, el core emulado y el customers falso de AccountsTestResource. Si un
// escenario falla, esta prueba falla; el detalle queda en build/reports/cucumber y en el XML de JUnit de Cucumber
@QuarkusTest
class TransferenciasBddTest extends IntegrationTest {

    @Test
    void theTransferScenariosPassInQuarkus() {
        byte status = Main.run(new String[]{
                "--glue", "com.alejandro.bank.accounts.bdd",
                "--plugin", "pretty",
                "--plugin", "html:build/reports/cucumber/transferencias.html",
                "--plugin", "junit:build/test-results/cucumber/TEST-transferencias.xml",
                "--monochrome",
                "classpath:features"}, Thread.currentThread().getContextClassLoader());

        assertThat(status).as("escenarios de transferencias.feature (detalle en build/reports/cucumber)").isZero();
    }
}
