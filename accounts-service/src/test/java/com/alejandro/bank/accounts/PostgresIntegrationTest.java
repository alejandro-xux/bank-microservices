package com.alejandro.bank.accounts;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Base de las pruebas de integración: un PostgreSQL real (misma versión que infra/docker-compose.yml)
// compartido por todas las clases que heredan de aquí, porque usan el mismo contexto de Spring.
// El JwtDecoder es simulado: estas pruebas no necesitan Keycloak (la validación real del token
// contra Keycloak ya se prueba en customers-service, BANK-39)
@SpringBootTest
@AutoConfigureMockMvc
public abstract class PostgresIntegrationTest {

    // Se arranca una vez para toda la ejecución; Testcontainers lo apaga al terminar
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    static {
        POSTGRES.start();
    }

    @MockitoBean
    protected JwtDecoder jwtDecoder;
}
