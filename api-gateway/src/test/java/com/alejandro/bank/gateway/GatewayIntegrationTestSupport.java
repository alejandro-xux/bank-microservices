package com.alejandro.bank.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
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
import java.util.concurrent.Executors;

// Base de las pruebas del gateway: un Keycloak real compartido por todas las clases (arranca una sola vez)
// y un customers-service simulado que anota cada petición que le llega. Cada clase trae su propio Redis.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class GatewayIntegrationTestSupport {

    // Lo que recibe el servicio simulado: path y header Authorization
    record Received(String path, String authorization) {
    }

    static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    static final HttpServer FAKE_CUSTOMERS = startFakeCustomersService();

    @SuppressWarnings("resource")
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.8.1")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withCopyFileToContainer(MountableFile.forHostPath("../infra/keycloak/bank-realm.json"),
                    "/opt/keycloak/data/import/bank-realm.json")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(2)));

    static {
        // Singleton container: Testcontainers lo elimina al terminar la JVM de pruebas
        KEYCLOAK.start();
    }

    // El gateway confía en este Keycloak y la ruta real de config-repo/ apunta al servicio simulado (sin Eureka)
    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", GatewayIntegrationTestSupport::issuerUri);
        registry.add("CUSTOMERS_SERVICE_URI", () -> "http://localhost:" + FAKE_CUSTOMERS.getAddress().getPort());
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper jsonMapper;

    @BeforeEach
    void clearReceived() {
        RECEIVED.clear();
    }

    HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    // Token real pedido a Keycloak con el cliente bank-cli (password grant, solo local)
    String token(String username, String password) throws Exception {
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

    static String issuerUri() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/bank";
    }

    static final Duration SLOW_RESPONSE = Duration.ofSeconds(2);

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
                if (path.endsWith("/lento")) {
                    // Servicio lento: tarda más que el timeout del gateway en las pruebas
                    sleep(SLOW_RESPONSE);
                    status = 200;
                    contentType = "application/json";
                    body = "{\"id\":\"lento\"}";
                } else if (path.endsWith("/error")) {
                    status = 500;
                    contentType = "application/problem+json";
                    body = "{\"title\":\"Error interno\",\"status\":500}";
                } else if (path.endsWith("/def-456")) {
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
            // Varios hilos: una respuesta lenta no bloquea a las demás
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
