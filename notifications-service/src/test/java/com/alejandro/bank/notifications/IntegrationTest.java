package com.alejandro.bank.notifications;

import com.alejandro.bank.contracts.customers.v1.CustomerContact;
import com.alejandro.bank.contracts.customers.v1.CustomersServiceGrpc;
import com.alejandro.bank.contracts.customers.v1.GetContactRequest;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureInProcessTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor;
import org.springframework.grpc.server.security.GrpcSecurity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Base de las pruebas de integración: PostgreSQL, Kafka y Mailpit reales (mismas versiones que
// infra/docker-compose.yml, Kafka sin creación automática de topics) y un customers-service simulado como servidor gRPC EN MEMORIA (mismo
// contrato, contracts/customers.proto). Las clases que heredan comparten los contenedores.
// Cada contexto de Spring consume con su PROPIO grupo: Spring deja vivos en caché los contextos de otras clases,
// y con el mismo grupo sus listeners se repartirían las particiones (el evento de una prueba le llegaría a otra)
@SpringBootTest(properties = "spring.kafka.consumer.group-id=notifications-test-${random.uuid}")
@AutoConfigureInProcessTransport
@Import(IntegrationTest.FakeCustomersService.class)
public abstract class IntegrationTest {

    // Clientes que conoce el customers-service simulado
    protected static final String ANA = "c15a442d-5146-471e-8804-746f4b42253b";
    protected static final String LUIS = "08d38a3b-c398-48fb-be36-3d6a1dde7ad2";
    protected static final String MISSING = "0e0e0e0e-0000-4000-8000-000000000404";
    protected static final String DOWN = "d0d0d0d0-0000-4000-8000-000000000500";
    protected static final String SLOW = "51015101-0000-4000-8000-000000000003";

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    // SMTP de prueba: el mismo Mailpit del compose, con su API para leer lo que llegó (MailpitInbox)
    protected static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.31.4")
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/readyz").forPort(8025));

    // Cada authorization que recibió el customers-service simulado, en orden
    protected static final Queue<String> RECEIVED_AUTHORIZATIONS = new ConcurrentLinkedQueue<>();

    static {
        POSTGRES.start();
        KAFKA.start();
        MAILPIT.start();
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TransferTopics.TOPIC, 3, (short) 1),
                    new NewTopic(TransferTopics.DEAD_LETTER_TOPIC, 3, (short) 1))).all().get();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudieron crear los topics de prueba", ex);
        }
    }

    // Las propiedades dinámicas ganan a las de config-repo (que se importan y pisarían a application-test.yaml)
    @DynamicPropertySource
    static void smtp(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    }

    protected static MailpitInbox inbox() {
        return new MailpitInbox("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025));
    }

    // Publica como el relay de accounts-service (BANK-69): bytes del Protobuf y los headers event-type y event-id.
    // Sin event-type el consumidor no sabe qué mensaje es (BANK-80)
    protected static void publish(String key, String eventType, byte[] value) throws Exception {
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(TransferTopics.TOPIC, key, value);
            // eventType null: sin el header, como un productor roto (BANK-111)
            if (eventType != null) {
                record.headers().add(TransferTopics.HEADER_EVENT_TYPE, eventType.getBytes(StandardCharsets.UTF_8));
            }
            record.headers().add(TransferTopics.HEADER_EVENT_ID, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            producer.send(record).get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeCustomersService {

        private static final Metadata.Key<String> AUTHORIZATION =
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
        private static final ScheduledExecutorService LATER = Executors.newSingleThreadScheduledExecutor();

        // Lo que se prueba es notifications: el simulado no valida tokens (eso lo prueba customers-service)
        @Bean
        @GlobalServerInterceptor
        AuthenticationProcessInterceptor fakeCustomersAuthorization(GrpcSecurity grpc) throws Exception {
            return grpc.authorizeRequests(requests -> requests.allRequests().permitAll()).build();
        }

        @Bean
        @GlobalServerInterceptor
        ServerInterceptor recordAuthorization() {
            return new ServerInterceptor() {
                @Override
                public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                                   ServerCallHandler<Q, R> next) {
                    String authorization = headers.get(AUTHORIZATION);
                    RECEIVED_AUTHORIZATIONS.add(authorization == null ? "(sin token)" : authorization);
                    return next.startCall(call, headers);
                }
            };
        }

        @Bean
        CustomersServiceGrpc.CustomersServiceImplBase fakeCustomersGrpc() {
            return new CustomersServiceGrpc.CustomersServiceImplBase() {
                @Override
                public void getContact(GetContactRequest request, StreamObserver<CustomerContact> response) {
                    switch (request.getId()) {
                        case ANA -> reply(response, ANA, "Ana Quispe", "ana@mail.com");
                        case LUIS -> reply(response, LUIS, "Luis Rojas", "luis@mail.com");
                        case DOWN -> response.onError(Status.UNAVAILABLE.asRuntimeException());
                        // Más lento que el deadline de 2 s
                        case SLOW -> LATER.schedule(() -> reply(response, SLOW, "Lento", "lento@mail.com"), 3, TimeUnit.SECONDS);
                        default -> response.onError(Status.NOT_FOUND.asRuntimeException());
                    }
                }
            };
        }

        private static void reply(StreamObserver<CustomerContact> response, String id, String name, String email) {
            try {
                response.onNext(CustomerContact.newBuilder().setId(id).setName(name).setEmail(email).build());
                response.onCompleted();
            } catch (IllegalStateException alreadyCancelled) {
                // El cliente ya se fue por el deadline (caso SLOW)
            }
        }
    }
}
