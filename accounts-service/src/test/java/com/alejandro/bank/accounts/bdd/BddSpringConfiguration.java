package com.alejandro.bank.accounts.bdd;

import com.alejandro.bank.accounts.PostgresIntegrationTest;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;

// El contexto de Spring de los escenarios (BANK-87): el mismo de las pruebas de integración (PostgreSQL, Kafka y el
// mainframe emulado reales, customers-service simulado). El recuperador cuenta las pendientes enseguida, para que el
// escenario "pasa el recuperador" no tenga que esperar 10 s
@CucumberContextConfiguration
@TestPropertySource(properties = "bank.saga.recovery.min-age=0s")
public class BddSpringConfiguration extends PostgresIntegrationTest {

    // Los clientes y tokens de la base, para los pasos (que no heredan de ella)
    static final String ANA_ID = ANA;
    static final String LUIS_ID = LUIS;
    static final String ANA_TOKEN = CUSTOMER_TOKEN;
    static final String LUIS_BEARER = LUIS_TOKEN;

    static void prepareTokens(JwtDecoder decoder) {
        stubTokens(decoder);
    }
}
