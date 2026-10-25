package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.domain.AccountNumber;
import com.alejandro.bank.accounts.outbox.OutboxRelay;
import com.alejandro.bank.accounts.service.TransferService;
import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BANK-69: cada transferencia deja su evento en el outbox en la misma transacción, y el relay lo publica
// en Kafka sin perderlo ni desordenarlo. PostgreSQL y Kafka reales (Testcontainers)
class OutboxIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxRelay relay;

    @Autowired
    TransferService transferService;

    UUID anaPen;
    UUID luisPen;
    String anaPenNumber = AccountNumber.of(930_001);
    String luisPenNumber = AccountNumber.of(930_002);

    @BeforeEach
    void accounts() {
        jdbc.update("delete from outbox");
        jdbc.update("delete from movement");
        jdbc.update("delete from transfer");
        jdbc.update("delete from account");
        anaPen = insertAccount(ANA, anaPenNumber, "1000.00");
        luisPen = insertAccount(LUIS, luisPenNumber, "1000.00");
    }

    @Test
    void aTransferLeavesItsEventAndTheRelayPublishesIt() throws Exception {
        String response = transfer(CUSTOMER_TOKEN, UUID.randomUUID(), anaPen, luisPenNumber, "150")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String transferId = JsonPath.read(response, "$.id");
        UUID eventId = jdbc.queryForObject("select id from outbox where published_at is null", UUID.class);

        assertThat(relay.publishBatch()).isEqualTo(1);

        ConsumerRecord<String, byte[]> record = readEvents(Set.of(eventId)).getFirst();
        TransferCompleted event = TransferCompleted.parseFrom(record.value());
        assertThat(record.key()).isEqualTo(anaPen.toString());
        assertThat(header(record, TransferTopics.HEADER_EVENT_TYPE)).isEqualTo("TransferCompleted");
        assertThat(header(record, TransferTopics.HEADER_EVENT_ID)).isEqualTo(eventId.toString());
        assertThat(event.getEventId()).isEqualTo(eventId.toString());
        assertThat(event.getTransferId()).isEqualTo(transferId);
        assertThat(event.getFromAccountId()).isEqualTo(anaPen.toString());
        assertThat(event.getFromAccountNumber()).isEqualTo(anaPenNumber);
        assertThat(event.getToAccountId()).isEqualTo(luisPen.toString());
        assertThat(event.getToAccountNumber()).isEqualTo(luisPenNumber);
        assertThat(event.getFromCustomerId()).isEqualTo(ANA);
        assertThat(event.getToCustomerId()).isEqualTo(LUIS);
        assertThat(event.getAmount()).isEqualTo("150.00");
        assertThat(event.getCurrency()).isEqualTo("PEN");
        assertThat(event.getCompletedAt().getSeconds()).isPositive();
        // Ya publicado: la próxima vuelta no lo manda otra vez
        assertThat(jdbc.queryForObject("select count(*) from outbox where published_at is null", Integer.class)).isZero();
        assertThat(relay.publishBatch()).isZero();
    }

    @Test
    void aRetryWithTheSameKeyDoesNotCreateASecondEvent() throws Exception {
        UUID key = UUID.randomUUID();
        transfer(CUSTOMER_TOKEN, key, anaPen, luisPenNumber, "100.00").andExpect(status().isCreated());
        transfer(CUSTOMER_TOKEN, key, anaPen, luisPenNumber, "100.00").andExpect(status().isCreated());

        assertThat(events()).isEqualTo(1);
    }

    @Test
    void aDoubleClickCreatesOneEvent() throws Exception {
        UUID key = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> clicks = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                clicks.add(pool.submit((Callable<Void>) () -> {
                    start.await();
                    transferService.transfer(key, anaPen, luisPenNumber, new BigDecimal("100.00"), admin());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> click : clicks) {
                click.get();
            }
        }

        assertThat(balance(anaPen)).isEqualTo("900.00");
        assertThat(events()).isEqualTo(1);
    }

    @Test
    void aRejectedTransferLeavesNoEvent() throws Exception {
        transfer(CUSTOMER_TOKEN, UUID.randomUUID(), anaPen, luisPenNumber, "5000.00")
                .andExpect(status().isUnprocessableContent());

        assertThat(events()).isZero();
    }

    @Test
    void twoRelaysAtOnceKeepTheOrderOfEachAccountAndPublishEveryEventOnce() throws Exception {
        // 300 transferencias de 0.01: Ana → Luis y Luis → Ana alternadas. Tres lotes de 100
        for (int i = 0; i < 300; i++) {
            boolean fromAna = i % 2 == 0;
            transferService.transfer(UUID.randomUUID(), fromAna ? anaPen : luisPen,
                    fromAna ? luisPenNumber : anaPenNumber, new BigDecimal("0.01"), admin());
        }
        Map<String, List<String>> expected = expectedOrderPerAccount();

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> relays = new ArrayList<>();
            for (int r = 0; r < 2; r++) {
                relays.add(pool.submit(() -> {
                    start.await();
                    int published = 0;
                    int batch;
                    while ((batch = relay.publishBatch()) != 0) {
                        if (batch > 0) {
                            published += batch;
                        }
                    }
                    return published;
                }));
            }
            start.countDown();
            assertThat(relays.get(0).get() + relays.get(1).get()).isEqualTo(300);
        }

        Set<UUID> ids = expected.values().stream().flatMap(List::stream).map(UUID::fromString).collect(Collectors.toSet());
        List<ConsumerRecord<String, byte[]>> records = readEvents(ids);
        assertThat(records).hasSize(300);
        Map<String, List<String>> actual = new HashMap<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            actual.computeIfAbsent(record.key(), k -> new ArrayList<>()).add(header(record, TransferTopics.HEADER_EVENT_ID));
        }
        // Cada cuenta en una sola partición y en el mismo orden en que se movió el dinero
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void withKafkaDownTransfersStillWorkAndTheEventGoesOutWhenItReturns() throws Exception {
        var docker = KAFKA.getDockerClient();
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            transfer(CUSTOMER_TOKEN, UUID.randomUUID(), anaPen, luisPenNumber, "10.00").andExpect(status().isCreated());

            long start = System.nanoTime();
            assertThatThrownBy(() -> relay.publishBatch()).isInstanceOf(RuntimeException.class);
            // Falla por los timeouts del productor (config-repo), no a los 60 s de los valores por defecto
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(20));
            assertThat(jdbc.queryForObject("select count(*) from outbox where published_at is null", Integer.class)).isEqualTo(1);
        } finally {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        UUID eventId = jdbc.queryForObject("select id from outbox", UUID.class);
        assertThat(relay.publishBatch()).isEqualTo(1);
        // "Al menos una vez", no "exactamente una": el envío que venció quedó en camino y Kafka lo guardó al
        // despertar, y el relay lo mandó de nuevo (el productor idempotente solo evita duplicados entre sus
        // propios reintentos, no cuando la aplicación se rinde y reenvía). Todas las copias traen el mismo
        // event_id: con él las descarta el consumidor (BANK-72)
        List<ConsumerRecord<String, byte[]>> copies = readEvents(Set.of(eventId));
        assertThat(copies).isNotEmpty()
                .allSatisfy(copy -> assertThat(header(copy, TransferTopics.HEADER_EVENT_ID)).isEqualTo(eventId.toString()));
    }

    // ---------------------------------------------------------------------------------------------------

    private int events() {
        return jdbc.queryForObject("select count(*) from outbox", Integer.class);
    }

    // event_id de cada cuenta origen, en el orden del outbox (seq)
    private Map<String, List<String>> expectedOrderPerAccount() {
        Map<String, List<String>> expected = new HashMap<>();
        jdbc.query("select message_key, id from outbox order by seq", rs -> {
            expected.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
        });
        return expected;
    }

    // Lee el topic desde el principio y se queda con los eventos pedidos (el topic es de toda la suite)
    private static List<ConsumerRecord<String, byte[]>> readEvents(Set<UUID> eventIds) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        Set<String> wanted = eventIds.stream().map(UUID::toString).collect(Collectors.toSet());
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config)) {
            List<TopicPartition> partitions = consumer.partitionsFor(TransferTopics.TOPIC).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.currentTimeMillis() + 15_000;
            while (found.size() < wanted.size() && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(300))) {
                    if (wanted.contains(header(record, TransferTopics.HEADER_EVENT_ID))) {
                        found.add(record);
                    }
                }
            }
            // Un poco más: si hubiera duplicados, que también aparezcan
            for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofSeconds(1))) {
                if (wanted.contains(header(record, TransferTopics.HEADER_EVENT_ID))) {
                    found.add(record);
                }
            }
        }
        return found;
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private ResultActions transfer(String token, UUID key, UUID from, String toNumber, String amount) throws Exception {
        return mvc.perform(post("/transfers")
                .header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\":\"" + from + "\",\"toAccountNumber\":\"" + toNumber + "\",\"amount\":\"" + amount + "\"}"));
    }

    private UUID insertAccount(String customerId, String number, String balance) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into account (id, number, customer_id, type, currency, balance, status, created_at, updated_at) "
                + "values (?, ?, ?, 'AHORROS', 'PEN', ?, 'ACTIVE', now(), now())", id, number, customerId, new BigDecimal(balance));
        return id;
    }

    private String balance(UUID id) {
        return jdbc.queryForObject("select balance::text from account where id = ?", String.class, id);
    }

    private static JwtAuthenticationToken admin() {
        Jwt jwt = Jwt.withTokenValue("token-asesor").header("alg", "RS256").subject("7b2e9d4c").claim("oid", "7b2e9d4c")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        return new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
    }
}
