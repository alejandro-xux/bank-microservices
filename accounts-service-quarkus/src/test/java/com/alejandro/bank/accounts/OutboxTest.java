package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.domain.AccountNumber;
import com.alejandro.bank.accounts.outbox.OutboxRelay;
import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import com.google.protobuf.Descriptors;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Cada transferencia deja su evento en el outbox en la misma transacción, y el relay lo publica en Kafka sin perderlo
// ni desordenarlo: los mismos casos que OutboxIntegrationTest de accounts-service (BANK-69), con PostgreSQL y Kafka
// reales, más la comparación del evento con el contrato campo por campo
@QuarkusTest
class OutboxTest extends IntegrationTest {

    @Inject
    OutboxRelay relay;

    UUID anaPen;
    UUID luisPen;
    String anaPenNumber = AccountNumber.of(930_001);
    String luisPenNumber = AccountNumber.of(930_002);

    @BeforeEach
    void accounts() {
        emptyBank();
        anaPen = insertAccount(FakeCustomers.ANA, anaPenNumber, "PEN", "1000.00", "ACTIVE");
        luisPen = insertAccount(FakeCustomers.LUIS, luisPenNumber, "PEN", "1000.00", "ACTIVE");
    }

    @Test
    void aTransferLeavesItsEventAndTheRelayPublishesIt() throws Exception {
        String transferId = transfer(Tokens.customer(Tokens.ANA_OID), UUID.randomUUID(), anaPen, luisPenNumber, "150")
                .statusCode(201).extract().path("id");
        UUID eventId = UUID.fromString(queryString("select id::text from outbox where published_at is null"));

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
        assertThat(event.getFromCustomerId()).isEqualTo(FakeCustomers.ANA);
        assertThat(event.getToCustomerId()).isEqualTo(FakeCustomers.LUIS);
        assertThat(event.getAmount()).isEqualTo("150.00");
        assertThat(event.getCurrency()).isEqualTo("PEN");
        assertThat(event.getCompletedAt().getSeconds()).isPositive();
        // Ya publicado: la próxima vuelta no lo manda otra vez
        assertThat(queryString("select count(*)::text from outbox where published_at is null")).isEqualTo("0");
        assertThat(relay.publishBatch()).isZero();
    }

    @Test
    void theEventMatchesTheContractFieldByFieldLikeSpringsDoes() throws Exception {
        transfer(Tokens.customer(Tokens.ANA_OID), UUID.randomUUID(), anaPen, luisPenNumber, "150.50").statusCode(201);
        UUID eventId = UUID.fromString(queryString("select id::text from outbox"));
        relay.publishBatch();

        ConsumerRecord<String, byte[]> record = readEvents(Set.of(eventId)).getFirst();
        TransferCompleted event = TransferCompleted.parseFrom(record.value());
        // Todos los campos del contrato con valor (ninguno quedó en su valor por defecto) y ninguno desconocido: el
        // consumidor de notifications lee exactamente lo mismo que de un evento de Spring
        assertThat(event.getAllFields().keySet()).extracting(Descriptors.FieldDescriptor::getName)
                .containsExactlyInAnyOrderElementsOf(TransferCompleted.getDescriptor().getFields().stream()
                        .map(Descriptors.FieldDescriptor::getName).toList());
        assertThat(event.getUnknownFields().asMap()).isEmpty();
        // Los mismos dos headers que pone Spring, nada más
        assertThat(Arrays.stream(record.headers().toArray()).map(Header::key))
                .containsExactly(TransferTopics.HEADER_EVENT_TYPE, TransferTopics.HEADER_EVENT_ID);
        // La fecha de completada es la de la transferencia resuelta (resolved_at), con nanos, como en Spring
        String resolvedAt = queryString("select to_char(resolved_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') from transfer");
        assertThat(java.time.Instant.ofEpochSecond(event.getCompletedAt().getSeconds(), event.getCompletedAt().getNanos()))
                .isEqualTo(java.time.Instant.parse(resolvedAt));
        // Bytes = el mismo mensaje serializado otra vez (codificación canónica de Protobuf)
        assertThat(record.value()).isEqualTo(event.toByteArray());
    }

    @Test
    void aRetryWithTheSameKeyDoesNotCreateASecondEvent() {
        UUID key = UUID.randomUUID();
        transfer(Tokens.customer(Tokens.ANA_OID), key, anaPen, luisPenNumber, "100.00").statusCode(201);
        transfer(Tokens.customer(Tokens.ANA_OID), key, anaPen, luisPenNumber, "100.00").statusCode(201);

        assertThat(events()).isEqualTo(1);
    }

    @Test
    void aDoubleClickCreatesOneEvent() throws Exception {
        UUID key = UUID.randomUUID();
        String admin = Tokens.admin();
        TransferTest.concurrently(5, i -> () -> transfer(admin, key, anaPen, luisPenNumber, "100.00").extract().statusCode());

        assertThat(balance(anaPen)).isEqualTo("900.00");
        assertThat(events()).isEqualTo(1);
    }

    @Test
    void aRejectedTransferLeavesNoEvent() {
        transfer(Tokens.customer(Tokens.ANA_OID), UUID.randomUUID(), anaPen, luisPenNumber, "5000.00").statusCode(422);

        assertThat(events()).isZero();
    }

    @Test
    void twoRelaysAtOnceKeepTheOrderOfEachAccountAndPublishEveryEventOnce() throws Exception {
        // 300 transferencias de 0.01: Ana → Luis y Luis → Ana alternadas. Tres lotes de 100
        String admin = Tokens.admin();
        for (int i = 0; i < 300; i++) {
            boolean fromAna = i % 2 == 0;
            transfer(admin, UUID.randomUUID(), fromAna ? anaPen : luisPen, fromAna ? luisPenNumber : anaPenNumber, "0.01")
                    .statusCode(201);
        }
        Map<String, List<String>> expected = expectedOrderPerAccount();

        List<Integer> published = TransferTest.concurrently(2, r -> () -> {
            int total = 0;
            int batch;
            while ((batch = relay.publishBatch()) != 0) {
                if (batch > 0) {
                    total += batch;
                }
            }
            return total;
        });
        assertThat(published.get(0) + published.get(1)).isEqualTo(300);

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
        var docker = AccountsTestResource.KAFKA.getDockerClient();
        docker.pauseContainerCmd(AccountsTestResource.KAFKA.getContainerId()).exec();
        try {
            transfer(Tokens.customer(Tokens.ANA_OID), UUID.randomUUID(), anaPen, luisPenNumber, "10.00").statusCode(201);

            long start = System.nanoTime();
            assertThatThrownBy(() -> relay.publishBatch()).isInstanceOf(RuntimeException.class);
            // Falla por los timeouts del productor (OutboxKafka), no a los 60 s de los valores por defecto
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(20));
            assertThat(queryString("select count(*)::text from outbox where published_at is null")).isEqualTo("1");
        } finally {
            docker.unpauseContainerCmd(AccountsTestResource.KAFKA.getContainerId()).exec();
        }

        UUID eventId = UUID.fromString(queryString("select id::text from outbox"));
        assertThat(relay.publishBatch()).isEqualTo(1);
        // "Al menos una vez": todas las copias traen el mismo event_id, con él las descarta el consumidor (BANK-72)
        List<ConsumerRecord<String, byte[]>> copies = readEvents(Set.of(eventId));
        assertThat(copies).isNotEmpty()
                .allSatisfy(copy -> assertThat(header(copy, TransferTopics.HEADER_EVENT_ID)).isEqualTo(eventId.toString()));
    }

    // ---------------------------------------------------------------------------------------------------

    private int events() {
        return Integer.parseInt(queryString("select count(*)::text from outbox"));
    }

    // event_id de cada cuenta origen, en el orden del outbox (seq)
    private Map<String, List<String>> expectedOrderPerAccount() {
        Map<String, List<String>> expected = new HashMap<>();
        String rows = queryString("select string_agg(message_key || ' ' || id, ',' order by seq) from outbox");
        for (String row : rows.split(",")) {
            String[] keyAndId = row.split(" ");
            expected.computeIfAbsent(keyAndId[0], k -> new ArrayList<>()).add(keyAndId[1]);
        }
        return expected;
    }

    // Lee el topic desde el principio y se queda con los eventos pedidos (el topic es de toda la suite)
    static List<ConsumerRecord<String, byte[]>> readEvents(Set<UUID> eventIds) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, AccountsTestResource.KAFKA.getBootstrapServers(),
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

    static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static ValidatableResponse transfer(String token, UUID key, UUID from, String toNumber, String amount) {
        return TransferTest.transfer(token, key, from, toNumber, amount);
    }
}
