package com.alejandro.bank.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Ruta /api/accounts/** y /api/transfers/** (BANK-58) frente al servicio simulado: enruta, reenvía token e
// Idempotency-Key, tiene su propio balde de rate limit y su propio circuito, independiente del de customers.
// Ventana de 4 llamadas para que los circuitos se abran rápido
@Testcontainers
class AccountsRouteIntegrationTest extends GatewayIntegrationTestSupport {

    private static final int WINDOW = 4;
    // Alcanza para las pruebas de circuito (hasta 8 llamadas) y se agota rápido en la de rate limit
    private static final int BURST = 10;

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.8.3-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void accountsRouteProperties(DynamicPropertyRegistry registry) {
        registry.add("REDIS_HOST", redis::getHost);
        registry.add("REDIS_PORT", () -> redis.getMappedPort(6379));
        // Balde chico (sin recarga en la prueba) para ver que cada ruta tiene el suyo
        registry.add("RATE_LIMIT_REPLENISH", () -> 1);
        registry.add("RATE_LIMIT_BURST", () -> BURST);
        for (String circuit : new String[] {"customers", "accounts"}) {
            registry.add("resilience4j.circuitbreaker.instances." + circuit + ".sliding-window-size", () -> WINDOW);
            registry.add("resilience4j.circuitbreaker.instances." + circuit + ".minimum-number-of-calls", () -> WINDOW);
        }
    }

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    String ana;

    @BeforeEach
    void reset() throws Exception {
        circuitBreakers.circuitBreaker("customers").reset();
        circuitBreakers.circuitBreaker("accounts").reset();
        redis.execInContainer("redis-cli", "FLUSHALL");
        ana = token("ana", "ana-local");
    }

    @Test
    void accountsPathsArriveWithoutTheApiPrefixAndWithTheSameToken() throws Exception {
        assertThat(get("/api/accounts/me", ana).statusCode()).isEqualTo(200);
        assertThat(get("/api/accounts/abc-123/movements", ana).statusCode()).isEqualTo(200);

        assertThat(RECEIVED).extracting(Received::path).containsExactly("/accounts/me", "/accounts/abc-123/movements");
        assertThat(RECEIVED).extracting(Received::authorization).containsOnly("Bearer " + ana);
    }

    @Test
    void transfersCarryTheIdempotencyKeyBothWays() throws Exception {
        String key = UUID.randomUUID().toString();

        HttpResponse<String> response = post("/api/transfers", ana, key, "{\"amount\":\"10.00\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(RECEIVED.getLast().path()).isEqualTo("/transfers");
        assertThat(RECEIVED.getLast().idempotencyKey()).isEqualTo(key);
    }

    @Test
    void withoutTokenTheGatewayAnswers401AndAccountsIsNotCalled() throws Exception {
        assertThat(get("/api/accounts/me", null).statusCode()).isEqualTo(401);
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void anOpenAccountsCircuitDoesNotAffectCustomers() throws Exception {
        for (int i = 0; i < WINDOW; i++) {
            assertThat(get("/api/accounts/error", ana).statusCode()).isEqualTo(503);
        }

        assertThat(circuit("accounts").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuit("customers").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(get("/api/customers/me", ana).statusCode()).isEqualTo(200);
    }

    @Test
    void anOpenCustomersCircuitDoesNotAffectAccounts() throws Exception {
        for (int i = 0; i < WINDOW; i++) {
            assertThat(get("/api/customers/error", ana).statusCode()).isEqualTo(503);
        }

        assertThat(circuit("customers").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuit("accounts").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(get("/api/accounts/me", ana).statusCode()).isEqualTo(200);
    }

    @Test
    void accountsOwn503DoesNotOpenItsCircuit() throws Exception {
        // El 503 de accounts significa "customers-service no está", no "accounts está enfermo"
        for (int i = 0; i < WINDOW * 2; i++) {
            HttpResponse<String> response = get("/api/accounts/sin-dependencia", ana);
            assertThat(response.statusCode()).isEqualTo(503);
        }

        assertThat(circuit("accounts").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        // Llegaron todas: el gateway no las cortó
        assertThat(RECEIVED).hasSize(WINDOW * 2);
    }

    @Test
    void eachRouteHasItsOwnRateLimitBucket() throws Exception {
        // Agota el balde de customers (con margen por si se recarga una ficha en medio)
        int status = 200;
        for (int i = 0; i < BURST + 3 && status != 429; i++) {
            status = get("/api/customers/me", ana).statusCode();
        }
        assertThat(status).isEqualTo(429);

        // El balde de customers está vacío, pero el de accounts está entero
        assertThat(get("/api/accounts/me", ana).statusCode()).isEqualTo(200);
    }

    private CircuitBreaker circuit(String name) {
        return circuitBreakers.circuitBreaker(name);
    }
}
