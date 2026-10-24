package com.alejandro.bank.gateway;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Rate limiting por usuario con un Redis real (BANK-46). Balde chico para provocar el 429 rápido:
// 1 ficha por segundo y ráfaga de 3. La última prueba apaga Redis, por eso el orden importa.
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RateLimitIntegrationTest extends GatewayIntegrationTestSupport {

    private static final int BURST = 3;

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.8.3-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void rateLimitProperties(DynamicPropertyRegistry registry) {
        registry.add("REDIS_HOST", redis::getHost);
        registry.add("REDIS_PORT", () -> redis.getMappedPort(6379));
        registry.add("RATE_LIMIT_REPLENISH", () -> 1);
        registry.add("RATE_LIMIT_BURST", () -> BURST);
    }

    @Test
    @Order(1)
    void exceedingTheLimitReturns429ProblemDetailWithoutCallingTheService() throws Exception {
        String ana = token("ana", "ana-local");

        List<Integer> statuses = new ArrayList<>();
        HttpResponse<String> limited = null;
        for (int i = 0; i < 10 && limited == null; i++) {
            HttpResponse<String> response = get("/api/customers/me", ana);
            statuses.add(response.statusCode());
            if (response.statusCode() == 429) {
                limited = response;
            }
        }

        // La ráfaga pasa completa y después llega el 429
        assertThat(statuses.subList(0, BURST)).containsOnly(200);
        assertThat(limited).isNotNull();
        assertThat(limited.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(limited.headers().firstValue("X-RateLimit-Remaining")).hasValue("0");
        assertThat(limited.headers().firstValue("X-RateLimit-Replenish-Rate")).hasValue("1");
        assertThat(limited.headers().firstValue("X-RateLimit-Burst-Capacity")).hasValue(String.valueOf(BURST));
        JsonNode body = jsonMapper.readTree(limited.body());
        assertThat(body.at("/status").asInt()).isEqualTo(429);
        assertThat(body.at("/title").asString()).isEqualTo("Demasiadas solicitudes");
        assertThat(body.at("/instance").asString()).isEqualTo("/api/customers/me");
        // Solo las peticiones permitidas llegaron al servicio
        assertThat(RECEIVED).hasSize((int) statuses.stream().filter(status -> status == 200).count());
    }

    // El balde es por usuario: con Ana limitada, el asesor sigue pasando
    @Test
    @Order(2)
    void anotherUserIsNotLimited() throws Exception {
        String ana = token("ana", "ana-local");
        String asesor = token("asesor", "asesor-local");
        for (int i = 0; i < 10; i++) {
            get("/api/customers/me", ana);
        }

        assertThat(get("/api/customers/me", ana).statusCode()).isEqualTo(429);
        assertThat(get("/api/customers/me", asesor).statusCode()).isEqualTo(200);
    }

    // Redis caído: el limitador deja pasar (falla abierta) y no cuelga la petición
    @Test
    @Order(3)
    void whenRedisIsDownRequestsPassFast() throws Exception {
        String ana = token("ana", "ana-local");
        redis.stop();

        for (int i = 0; i < BURST + 3; i++) {
            Instant start = Instant.now();
            HttpResponse<String> response = get("/api/customers/me", ana);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(2));
        }
    }
}
