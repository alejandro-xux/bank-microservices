package com.alejandro.bank.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Canary de accounts (BANK-104): con pesos 90/10 cada petición va a una sola de las dos rutas del grupo "accounts",
// y la respuesta dice cuál la atendió (X-Served-By). Las dos rutas apuntan al servicio simulado: lo que se prueba es
// el reparto del gateway. Cada ruta tiene su propio circuito. El actuator (circuitos y refresh) es solo del asesor
@Testcontainers
class CanaryIntegrationTest extends GatewayIntegrationTestSupport {

    private static final int REQUESTS = 400;

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.8.3-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void canaryProperties(DynamicPropertyRegistry registry) {
        registry.add("REDIS_HOST", redis::getHost);
        registry.add("REDIS_PORT", () -> redis.getMappedPort(6379));
        registry.add("ACCOUNTS_QUARKUS_URI", () -> "http://localhost:" + FAKE_CUSTOMERS.getAddress().getPort());
        registry.add("ACCOUNTS_SPRING_WEIGHT", () -> 90);
        registry.add("ACCOUNTS_QUARKUS_WEIGHT", () -> 10);
        // Que el rate limit no corte la cuenta del reparto
        registry.add("RATE_LIMIT_REPLENISH", () -> 10_000);
        registry.add("RATE_LIMIT_BURST", () -> 10_000);
    }

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    String ana;
    String asesor;

    @BeforeEach
    void tokens() throws Exception {
        ana = token("ana", "ana-local");
        asesor = token("asesor", "asesor-local");
    }

    @Test
    void withWeights90And10AboutOneRequestInTenGoesToQuarkus() throws Exception {
        Map<String, Integer> servedBy = new HashMap<>();
        for (int i = 0; i < REQUESTS; i++) {
            HttpResponse<String> response = get("/api/accounts/me", ana);
            assertThat(response.statusCode()).isEqualTo(200);
            servedBy.merge(response.headers().firstValue("X-Served-By").orElse("nadie"), 1, Integer::sum);
        }

        // 10 % de 400 = 40; el reparto es al azar: entre 4 % y 18 % (más de 4 desvíos estándar a cada lado)
        assertThat(servedBy.keySet()).containsExactlyInAnyOrder("accounts-service", "accounts-service-quarkus");
        assertThat(servedBy.get("accounts-service-quarkus")).isBetween(REQUESTS * 4 / 100, REQUESTS * 18 / 100);
        assertThat(servedBy.get("accounts-service") + servedBy.get("accounts-service-quarkus")).isEqualTo(REQUESTS);
    }

    @Test
    void eachVersionHasItsOwnCircuit() {
        assertThat(circuitBreakers.circuitBreaker("accounts")).isNotSameAs(circuitBreakers.circuitBreaker("accounts-quarkus"));
        assertThat(circuitBreakers.getAllCircuitBreakers()).extracting(c -> c.getName())
                .contains("accounts", "accounts-quarkus");
    }

    @Test
    void onlyTheAdminCanSeeTheCircuitsAndApplyTheWeights() throws Exception {
        assertThat(get("/actuator/circuitbreakers", ana).statusCode()).isEqualTo(403);
        assertThat(get("/actuator/circuitbreakers", asesor).statusCode()).isEqualTo(200);
        assertThat(postWithoutBody("/actuator/refresh", ana).statusCode()).isEqualTo(403);
        assertThat(postWithoutBody("/actuator/refresh", asesor).statusCode()).isEqualTo(200);
        // El health sigue público (probes de AKS)
        assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> postWithoutBody(String path, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
}
