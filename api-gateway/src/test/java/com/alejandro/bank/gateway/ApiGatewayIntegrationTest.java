package com.alejandro.bank.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

// Gateway real con un Keycloak real (Testcontainers) delante de un customers-service simulado,
// que anota cada petición que le llega. El flujo con el servicio real se verifica a mano (E2E en E9).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApiGatewayIntegrationTest {

    // Lo que recibe el servicio simulado: path y header Authorization
    record Received(String path, String authorization) {
    }

    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer FAKE_CUSTOMERS = startFakeCustomersService();

    @Container
    static GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.8.1")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withCopyFileToContainer(MountableFile.forHostPath("../infra/keycloak/bank-realm.json"),
                    "/opt/keycloak/data/import/bank-realm.json")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(2)));

    // El gateway confía en este Keycloak y la ruta real de config-repo/ apunta al servicio simulado (sin Eureka)
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ApiGatewayIntegrationTest::issuerUri);
        registry.add("CUSTOMERS_SERVICE_URI",
                () -> "http://localhost:" + FAKE_CUSTOMERS.getAddress().getPort());
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper jsonMapper;

    @BeforeEach
    void clearReceived() {
        RECEIVED.clear();
    }

    @AfterAll
    static void stopFakeCustomersService() {
        FAKE_CUSTOMERS.stop(0);
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
        assertThat(RECEIVED).containsExactly(new Received("/customers/me", "Bearer " + token));
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

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    // Token real pedido a Keycloak con el cliente bank-cli (password grant, solo local)
    private String token(String username, String password) throws Exception {
        String form = "grant_type=password&client_id=bank-cli&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(issuerUri() + "/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        return jsonMapper.readTree(response.body()).at("/access_token").asString();
    }

    private static String issuerUri() {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080) + "/realms/bank";
    }

    // customers-service simulado: anota lo que recibe y responde según el id pedido
    private static HttpServer startFakeCustomersService() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                RECEIVED.add(new Received(path, exchange.getRequestHeaders().getFirst("Authorization")));
                int status;
                String contentType;
                String body;
                if (path.endsWith("/def-456")) {
                    status = 403;
                    contentType = "application/problem+json";
                    body = "{\"title\":\"Acceso denegado\",\"status\":403}";
                } else if (path.endsWith("/no-existe")) {
                    status = 404;
                    contentType = "application/problem+json";
                    body = "{\"title\":\"Recurso no encontrado\",\"status\":404}";
                } else {
                    status = 200;
                    contentType = "application/json";
                    body = "{\"id\":\"c15a442d\"}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", contentType);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
