package com.alejandro.bank.accounts;

import com.alejandro.bank.contracts.customers.v1.CustomerStatus;
import com.alejandro.bank.contracts.customers.v1.CustomerSummary;
import com.alejandro.bank.contracts.customers.v1.CustomersServiceGrpc;
import com.alejandro.bank.contracts.customers.v1.GetCustomerRequest;
import com.alejandro.bank.contracts.customers.v1.GetMyCustomerRequest;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureInProcessTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor;
import org.springframework.grpc.server.security.GrpcSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.when;

// Base de las pruebas de integración: un PostgreSQL, un Kafka y el mainframe emulado reales (mismas versiones y
// mappings que infra/docker-compose.yml) y un customers-service simulado como servidor gRPC EN MEMORIA (mismo contrato, contracts/customers.proto),
// compartidos por todas las clases que heredan de aquí porque usan el mismo contexto de Spring.
// El JwtDecoder es simulado: estas pruebas no necesitan Keycloak (la validación real del token contra
// Keycloak se prueba en customers-service)
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureInProcessTransport
@Import(PostgresIntegrationTest.FakeCustomersService.class)
public abstract class PostgresIntegrationTest {

    // Clientes que conoce el customers-service simulado
    protected static final String ANA = "c15a442d-5146-471e-8804-746f4b42253b";
    protected static final String BLOCKED = "b10c0ed0-0000-4000-8000-000000000001";
    protected static final String MISSING = "0e0e0e0e-0000-4000-8000-000000000404";
    protected static final String DOWN = "d0d0d0d0-0000-4000-8000-000000000500";
    protected static final String SLOW = "51015101-0000-4000-8000-000000000003";
    protected static final String LUIS = "1a1a1a1a-0000-4000-8000-000000000002";

    // Tokens simulados (rol CUSTOMER salvo el del asesor); GetMyCustomer responde según el token
    protected static final String ADMIN_TOKEN = "Bearer token-asesor";
    protected static final String CUSTOMER_TOKEN = "Bearer token-ana";
    protected static final String LUIS_TOKEN = "Bearer token-luis";
    protected static final String UNLINKED_TOKEN = "Bearer token-sin-vinculo";
    protected static final String CUSTOMERS_DOWN_TOKEN = "Bearer token-caido";

    // Se arranca una vez para toda la ejecución; Testcontainers lo apaga al terminar
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    // Kafka del outbox (BANK-69), como el del compose: sin creación automática de topics, así que se crean
    // aquí con las mismas particiones que infra/kafka/create-topics.sh. Las pruebas nunca tocan el Kafka local
    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    // El core bancario (BANK-78): el mismo WireMock y los mismos mappings que el compose, con sus reglas por céntimos
    // (.13 rechazo, .99 lento pero registrado, .98 core caído)
    protected static final GenericContainer<?> CORE_BANKING = new GenericContainer<>("wiremock/wiremock:3.13.2")
            .withCommand("--global-response-templating", "--disable-banner")
            .withCopyFileToContainer(MountableFile.forHostPath("../infra/core-banking-mock/mappings"), "/home/wiremock/mappings")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/__admin/health"));

    // Último authorization que recibió el customers-service simulado en la metadata (token relay)
    protected static final AtomicReference<String> RECEIVED_AUTHORIZATION = new AtomicReference<>();

    static {
        POSTGRES.start();
        KAFKA.start();
        CORE_BANKING.start();
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TransferTopics.TOPIC, 3, (short) 1),
                    new NewTopic(TransferTopics.DEAD_LETTER_TOPIC, 3, (short) 1))).all().get();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudieron crear los topics de prueba", ex);
        }
    }

    // Las propiedades dinámicas ganan a las de config-repo (que se importan y pisarían a application-test.yaml)
    @DynamicPropertySource
    static void coreBanking(DynamicPropertyRegistry registry) {
        registry.add("bank.core-banking.url", () -> "http://" + CORE_BANKING.getHost() + ":" + CORE_BANKING.getMappedPort(8080));
    }

    @MockitoBean
    protected JwtDecoder jwtDecoder;

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

    // customers-service simulado: responde según el id pedido o, en GetMyCustomer, según el token
    @TestConfiguration(proxyBeanMethods = false)
    static class FakeCustomersService {

        private static final Metadata.Key<String> AUTHORIZATION =
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
        private static final Context.Key<String> CALLER = Context.key("authorization");
        private static final ScheduledExecutorService LATER = Executors.newSingleThreadScheduledExecutor();

        // Lo que se prueba es accounts: el simulado no valida tokens (eso lo prueba customers-service)
        @Bean
        @GlobalServerInterceptor
        AuthenticationProcessInterceptor fakeCustomersAuthorization(GrpcSecurity grpc) throws Exception {
            return grpc.authorizeRequests(requests -> requests.allRequests().permitAll()).build();
        }

        // Anota el authorization recibido y lo deja en el Context de la llamada
        @Bean
        @GlobalServerInterceptor
        ServerInterceptor recordAuthorization() {
            return new ServerInterceptor() {
                @Override
                public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                                   ServerCallHandler<Q, R> next) {
                    String authorization = headers.get(AUTHORIZATION);
                    RECEIVED_AUTHORIZATION.set(authorization);
                    return Contexts.interceptCall(Context.current().withValue(CALLER, authorization), call, headers, next);
                }
            };
        }

        @Bean
        CustomersServiceGrpc.CustomersServiceImplBase fakeCustomersGrpc() {
            return new CustomersServiceGrpc.CustomersServiceImplBase() {

                @Override
                public void getCustomer(GetCustomerRequest request, StreamObserver<CustomerSummary> response) {
                    switch (request.getId()) {
                        case ANA -> reply(response, ANA, CustomerStatus.ACTIVE);
                        case BLOCKED -> reply(response, BLOCKED, CustomerStatus.BLOCKED);
                        case DOWN -> response.onError(Status.UNAVAILABLE.asRuntimeException());
                        // Más lento que el deadline de 2 s: responde a los 3 s sin bloquear un hilo
                        case SLOW -> LATER.schedule(() -> reply(response, SLOW, CustomerStatus.ACTIVE), 3, TimeUnit.SECONDS);
                        default -> response.onError(Status.NOT_FOUND.asRuntimeException());
                    }
                }

                @Override
                public void getMyCustomer(GetMyCustomerRequest request, StreamObserver<CustomerSummary> response) {
                    switch (String.valueOf(CALLER.get())) {
                        case CUSTOMER_TOKEN -> reply(response, ANA, CustomerStatus.ACTIVE);
                        case LUIS_TOKEN -> reply(response, LUIS, CustomerStatus.ACTIVE);
                        case CUSTOMERS_DOWN_TOKEN -> response.onError(Status.UNAVAILABLE.asRuntimeException());
                        // Sin cliente vinculado (como el asesor o una cuenta de acceso nueva)
                        default -> response.onError(Status.PERMISSION_DENIED.asRuntimeException());
                    }
                }
            };
        }

        private static void reply(StreamObserver<CustomerSummary> response, String id, CustomerStatus status) {
            try {
                response.onNext(CustomerSummary.newBuilder().setId(id).setStatus(status).build());
                response.onCompleted();
            } catch (IllegalStateException alreadyCancelled) {
                // El cliente ya se fue por el deadline (caso SLOW)
            }
        }
    }
}
