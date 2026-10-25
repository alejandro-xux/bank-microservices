package com.alejandro.bank.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

// Enrutamiento y seguridad en el borde (BANK-45), frente al customers-service simulado.
// El flujo con el servicio real se verifica a mano (E2E en E9).
@Testcontainers
class ApiGatewayIntegrationTest extends GatewayIntegrationTestSupport {

    // Redis propio para el rate limiting (límites por defecto: aquí no se agotan)
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.8.3-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("REDIS_HOST", redis::getHost);
        registry.add("REDIS_PORT", () -> redis.getMappedPort(6379));
    }

    @Test
    void healthIsPublic() throws Exception {
        assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
    }

    @Test
    void withoutTokenReturns401AtTheEdgeWithoutCallingTheService() throws Exception {
        HttpResponse<String> response = get("/api/customers/me", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        JsonNode body = jsonMapper.readTree(response.body());
        assertThat(body.at("/title").asString()).isEqualTo("No autenticado");
        assertThat(body.at("/instance").asString()).isEqualTo("/api/customers/me");
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void tamperedTokenReturns401WithoutCallingTheService() throws Exception {
        String token = token("ana", "ana-local");
        int i = token.length() - 10;
        String tampered = token.substring(0, i) + (token.charAt(i) == 'A' ? 'B' : 'A') + token.substring(i + 1);

        assertThat(get("/api/customers/me", tampered).statusCode()).isEqualTo(401);
        assertThat(RECEIVED).isEmpty();
    }

    // El servicio recibe el path sin /api y el mismo token, para validarlo otra vez (zero trust)
    @Test
    void validTokenIsForwardedWithoutApiPrefixAndWithTheSameAuthorization() throws Exception {
        String token = token("ana", "ana-local");

        HttpResponse<String> response = get("/api/customers/me", token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(RECEIVED).containsExactly(new Received("/customers/me", "Bearer " + token, null));
    }

    // El gateway no decide roles ni dueño: los 403 y 404 del servicio llegan tal cual
    @Test
    void serviceStatusesPassThroughUnchanged() throws Exception {
        String token = token("ana", "ana-local");

        HttpResponse<String> forbidden = get("/api/customers/def-456", token);
        HttpResponse<String> notFound = get("/api/customers/no-existe", token);

        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(forbidden.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(jsonMapper.readTree(forbidden.body()).at("/title").asString()).isEqualTo("Acceso denegado");
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(RECEIVED).extracting(Received::path).containsExactly("/customers/def-456", "/customers/no-existe");
    }
}
