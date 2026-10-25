package com.alejandro.bank.accounts;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.when;

// Base de las pruebas de integración: un PostgreSQL real (misma versión que infra/docker-compose.yml)
// y un customers-service simulado, compartidos por todas las clases que heredan de aquí porque usan
// el mismo contexto de Spring. El JwtDecoder es simulado: estas pruebas no necesitan Keycloak
// (la validación real del token contra Keycloak ya se prueba en customers-service, BANK-39)
@SpringBootTest
@AutoConfigureMockMvc
public abstract class PostgresIntegrationTest {

    // Clientes que conoce el customers-service simulado
    protected static final String ANA = "c15a442d-5146-471e-8804-746f4b42253b";
    protected static final String BLOCKED = "b10c0ed0-0000-4000-8000-000000000001";
    protected static final String MISSING = "0e0e0e0e-0000-4000-8000-000000000404";
    protected static final String DOWN = "d0d0d0d0-0000-4000-8000-000000000500";
    protected static final String SLOW = "51015101-0000-4000-8000-000000000003";
    protected static final String LUIS = "1a1a1a1a-0000-4000-8000-000000000002";

    // Tokens simulados (rol CUSTOMER salvo el del asesor); /customers/me responde según el token
    protected static final String ADMIN_TOKEN = "Bearer token-asesor";
    protected static final String CUSTOMER_TOKEN = "Bearer token-ana";
    protected static final String LUIS_TOKEN = "Bearer token-luis";
    protected static final String UNLINKED_TOKEN = "Bearer token-sin-vinculo";
    protected static final String CUSTOMERS_DOWN_TOKEN = "Bearer token-caido";

    // Se arranca una vez para toda la ejecución; Testcontainers lo apaga al terminar
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    // Último Authorization que recibió el customers-service simulado (token relay)
    protected static final AtomicReference<String> RECEIVED_AUTHORIZATION = new AtomicReference<>();
    static final HttpServer CUSTOMERS = fakeCustomersService();

    static {
        POSTGRES.start();
    }

    @MockitoBean
    protected JwtDecoder jwtDecoder;

    // Feign apunta al simulado en lugar de buscar customers-service en Eureka
    @DynamicPropertySource
    static void customersServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.customers-service.url",
                () -> "http://localhost:" + CUSTOMERS.getAddress().getPort());
    }

    @BeforeEach
    void tokens() {
        RECEIVED_AUTHORIZATION.set(null);
        when(jwtDecoder.decode("token-asesor")).thenReturn(jwt("token-asesor", "7b2e9d4c", "ADMIN"));
        when(jwtDecoder.decode("token-ana")).thenReturn(jwt("token-ana", "4f8c2a1e", "CUSTOMER"));
        when(jwtDecoder.decode("token-luis")).thenReturn(jwt("token-luis", "9c3d5e7f", "CUSTOMER"));
        when(jwtDecoder.decode("token-sin-vinculo")).thenReturn(jwt("token-sin-vinculo", "5e5e5e5e", "CUSTOMER"));
        when(jwtDecoder.decode("token-caido")).thenReturn(jwt("token-caido", "6f6f6f6f", "CUSTOMER"));
    }

    private static Jwt jwt(String value, String oid, String role) {
        return Jwt.withTokenValue(value)
                .header("alg", "RS256")
                .subject(oid)
                .claim("oid", oid)
                .claim("roles", List.of(role))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    // GET /customers/{id} con una respuesta distinta según el cliente, y GET /customers/me según el token
    private static HttpServer fakeCustomersService() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/customers/", exchange -> {
                RECEIVED_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String id = exchange.getRequestURI().getPath().substring("/customers/".length());
                if ("me".equals(id)) {
                    respondMe(exchange);
                    return;
                }
                switch (id) {
                    case ANA -> respond(exchange, 200, customer(ANA, "ACTIVE"));
                    case BLOCKED -> respond(exchange, 200, customer(BLOCKED, "BLOCKED"));
                    case DOWN -> respond(exchange, 500, "{\"status\":500}");
                    case SLOW -> {
                        sleep();
                        respond(exchange, 200, customer(SLOW, "ACTIVE"));
                    }
                    default -> respond(exchange, 404, "{\"status\":404}");
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void respondMe(HttpExchange exchange) throws IOException {
        switch (String.valueOf(RECEIVED_AUTHORIZATION.get())) {
            case CUSTOMER_TOKEN -> respond(exchange, 200, customer(ANA, "ACTIVE"));
            case LUIS_TOKEN -> respond(exchange, 200, customer(LUIS, "ACTIVE"));
            case CUSTOMERS_DOWN_TOKEN -> respond(exchange, 500, "{\"status\":500}");
            // Sin cliente vinculado (como el asesor o una cuenta de acceso nueva)
            default -> respond(exchange, 403, "{\"status\":403}");
        }
    }

    // Con campos que accounts-service no usa: deben ignorarse
    private static String customer(String id, String status) {
        return "{\"id\":\"" + id + "\",\"firstName\":\"Ana\",\"documentNumber\":\"12345678\",\"status\":\"" + status + "\"}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // El cliente ya cortó por timeout (caso SLOW)
        } finally {
            exchange.close();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
