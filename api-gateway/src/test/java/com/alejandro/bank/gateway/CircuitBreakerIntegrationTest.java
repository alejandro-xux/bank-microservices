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
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// Circuit breaker y timeouts del gateway (BANK-47) frente al customers-service simulado.
// Tiempos chicos para que la prueba sea rápida: timeout 500 ms, ventana de 4 llamadas, 1 s abierto.
@Testcontainers
class CircuitBreakerIntegrationTest extends GatewayIntegrationTestSupport {

    private static final int WINDOW = 4;

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.8.3-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void circuitBreakerProperties(DynamicPropertyRegistry registry) {
        registry.add("REDIS_HOST", redis::getHost);
        registry.add("REDIS_PORT", () -> redis.getMappedPort(6379));
        // El rate limiting no debe interferir en esta prueba
        registry.add("RATE_LIMIT_REPLENISH", () -> 1000);
        registry.add("RATE_LIMIT_BURST", () -> 1000);
        registry.add("spring.cloud.gateway.server.webflux.httpclient.response-timeout", () -> "500ms");
        registry.add("resilience4j.timelimiter.instances.customers.timeout-duration", () -> "500ms");
        registry.add("resilience4j.circuitbreaker.instances.customers.sliding-window-size", () -> WINDOW);
        registry.add("resilience4j.circuitbreaker.instances.customers.minimum-number-of-calls", () -> WINDOW);
        registry.add("resilience4j.circuitbreaker.instances.customers.wait-duration-in-open-state", () -> "1s");
        registry.add("resilience4j.circuitbreaker.instances.customers.permitted-number-of-calls-in-half-open-state", () -> 2);
    }

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    String ana;

    // Cada prueba empieza con el circuito CERRADO y sin historia
    @BeforeEach
    void resetCircuit() throws Exception {
        circuit().reset();
        ana = token("ana", "ana-local");
    }

    @Test
    void slowServiceReturns503FastWithProblemDetailAndNoInternalDetails() throws Exception {
        Instant start = Instant.now();
        HttpResponse<String> response = get("/api/customers/lento", ana);
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(response.statusCode()).isEqualTo(503);
        // Corta por el timeout (500 ms), no espera los 2 s del servicio lento
        assertThat(elapsed).isLessThan(SLOW_RESPONSE);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        JsonNode body = jsonMapper.readTree(response.body());
        assertThat(body.at("/title").asString()).isEqualTo("Servicio no disponible");
        assertThat(body.at("/instance").asString()).isEqualTo("/api/customers/lento");
        assertThat(response.body()).doesNotContain("localhost", "Exception", "timeout");
    }

    // Con 5xx seguidos el circuito se abre y el servicio deja de recibir llamadas
    @Test
    void repeated5xxOpenTheCircuitAndStopCallingTheService() throws Exception {
        for (int i = 0; i < WINDOW; i++) {
            HttpResponse<String> failed = get("/api/customers/error", ana);
            // El 500 del servicio se reemplaza por el plan B: 503 en ProblemDetail, sin el cuerpo original
            assertThat(failed.statusCode()).isEqualTo(503);
            assertThat(jsonMapper.readTree(failed.body()).at("/title").asString()).isEqualTo("Servicio no disponible");
        }
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsBefore = RECEIVED.size();

        Instant start = Instant.now();
        HttpResponse<String> response = get("/api/customers/me", ana);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofMillis(500));
        assertThat(RECEIVED).hasSize(callsBefore);
    }

    // Un 403 o 404 es una respuesta correcta del servicio: no abre el circuito
    @Test
    void repeated4xxDoNotOpenTheCircuit() throws Exception {
        for (int i = 0; i < WINDOW * 2; i++) {
            assertThat(get("/api/customers/def-456", ana).statusCode()).isEqualTo(403);
            assertThat(get("/api/customers/no-existe", ana).statusCode()).isEqualTo(404);
        }

        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // Abierto → (1 s) → semiabierto → las llamadas de prueba salen bien → cerrado
    @Test
    void afterTheWaitTheCircuitRecoversWhenTheServiceIsHealthy() throws Exception {
        for (int i = 0; i < WINDOW; i++) {
            get("/api/customers/error", ana);
        }
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(1500);
        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(get("/api/customers/me", ana).statusCode()).isEqualTo(200);
        assertThat(get("/api/customers/me", ana).statusCode()).isEqualTo(200);

        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private CircuitBreaker circuit() {
        return circuitBreakers.circuitBreaker("customers");
    }
}
