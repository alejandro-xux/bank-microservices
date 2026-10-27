package com.alejandro.bank.notifications.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import com.alejandro.bank.notifications.MailpitInbox;
import com.alejandro.bank.notifications.TokenMockedIntegrationTest;
import com.google.protobuf.Timestamp;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

// BANK-73: un aviso no se pierde porque el correo caiga, y un mensaje que nunca va a funcionar no frena a los demás.
// Kafka, PostgreSQL y Mailpit reales; la caída del correo es Mailpit pausado (docker pause)
class ResilienceIntegrationTest extends TokenMockedIntegrationTest {

    @Autowired
    DeliveryCircuit circuit;

    MailpitInbox inbox = inbox();

    @BeforeEach
    void clean() throws Exception {
        inbox.clear();
        circuit.reset();
    }

    @Test
    void unreadableAndInvalidMessagesGoToTheDeadLetterTopicAndTheNextOnesArrive() throws Exception {
        String marker = UUID.randomUUID().toString();
        byte[] unreadable = ("no es protobuf " + marker).getBytes(StandardCharsets.UTF_8);
        // Se lee bien, pero el event_id no es un UUID: inválido
        byte[] invalid = transfer("61.00").toBuilder().setEventId("invalido-" + marker).build().toByteArray();

        publish(unreadable);
        publish(invalid);
        publish(transfer("61.00").toByteArray());

        // El válido que venía detrás llega igual: los malos no bloquearon la partición
        assertThat(inbox.awaitAbout("61.00", 2)).hasSize(2);
        List<ConsumerRecord<String, byte[]>> dead = awaitDeadLetters(marker, 2);
        assertThat(dead).extracting(r -> new String(r.value(), StandardCharsets.ISO_8859_1).contains(marker)).containsOnly(true);
        assertThat(dead).extracting(r -> header(r, "kafka_dlt-exception-cause-fqcn") + " " + header(r, "kafka_dlt-exception-fqcn"))
                .anySatisfy(why -> assertThat(why).contains("DeserializationException"))
                .anySatisfy(why -> assertThat(why).contains("InvalidEventException"));
    }

    @Test
    void aMailOutageLosesNothingSendsNothingToTheDeadLetterTopicAndPausesTheConsumer() throws Exception {
        var docker = MAILPIT.getDockerClient();
        List<TransferCompleted> events = new ArrayList<>();
        docker.pauseContainerCmd(MAILPIT.getContainerId()).exec();
        try {
            for (int i = 0; i < 4; i++) {
                TransferCompleted event = transfer("62.00");
                events.add(event);
                publish(event.toByteArray());
            }
            // Con el correo caído el circuito se abre y el consumidor queda pausado: los avisos esperan en Kafka
            assertThat(await(Duration.ofSeconds(60), () -> circuit.state() == CircuitBreaker.State.OPEN)).isTrue();
            assertThat(circuit.health().getDetails()).containsEntry("state", "OPEN").containsEntry("consumerPaused", true);
            // La caída sigue un rato: pasan dos pruebas de medio abierto que fallan. Con reintentos acotados, aquí ya
            // se agotarían y el aviso iría al DLT (o se perdería, con la configuración por defecto sin pausa)
            Thread.sleep(25_000);
        } finally {
            docker.unpauseContainerCmd(MAILPIT.getContainerId()).exec();
        }

        // Vuelve el correo: llegan los 8 (4 transferencias × "Enviaste" y "Recibiste"), ninguno repetido
        assertThat(inbox.awaitAbout("62.00", 8)).hasSize(8);
        assertThat(await(Duration.ofSeconds(30), () -> circuit.state() == CircuitBreaker.State.CLOSED)).isTrue();
        // Y ninguno fue a parar al DLT
        List<String> ids = events.stream().map(TransferCompleted::getEventId).toList();
        assertThat(readDeadLetters()).noneMatch(r -> ids.stream().anyMatch(id -> new String(r.value(), StandardCharsets.ISO_8859_1).contains(id)));
    }

    // ---------------------------------------------------------------------------------------------------

    // BANK-111: un mensaje sin header event-type está roto (nadie puede saber qué es): va al DLT sin reintentos, y el
    // consumidor sigue con el que viene detrás. Un tipo desconocido CON header se sigue ignorando (TransferFailedIntegrationTest)
    @Test
    void aMessageWithoutEventTypeGoesToTheDeadLetterTopic() throws Exception {
        publish("cuenta-de-prueba", null, transfer("sin-header-event-type").toByteArray());

        List<ConsumerRecord<String, byte[]>> dead = awaitDeadLetters("sin-header-event-type", 1);
        assertThat(dead).hasSize(1);
        // Como un ilegible: falla al deserializar, sin reintentos
        assertThat(header(dead.getFirst(), "kafka_dlt-exception-fqcn")).contains("DeserializationException");
    }

    private static TransferCompleted transfer(String amount) {
        return TransferCompleted.newBuilder()
                .setEventId(UUID.randomUUID().toString()).setTransferId(UUID.randomUUID().toString())
                .setFromAccountId(UUID.randomUUID().toString()).setFromAccountNumber("001-0000000001-6")
                .setToAccountId(UUID.randomUUID().toString()).setToAccountNumber("001-0000000003-2")
                .setFromCustomerId(ANA).setToCustomerId(LUIS)
                .setAmount(amount).setCurrency("PEN")
                .setCompletedAt(Timestamp.newBuilder().setSeconds(1792936800L))
                .build();
    }

    // Todos con la misma clave: misma partición, en orden (los malos van ANTES del válido). Todos dicen ser un
    // TransferCompleted: los ilegibles e inválidos son de un tipo conocido, por eso van al DLT y no se ignoran
    private static void publish(byte[] value) throws Exception {
        publish("cuenta-de-prueba", TransferTopics.TRANSFER_COMPLETED, value);
    }

    private static List<ConsumerRecord<String, byte[]>> awaitDeadLetters(String marker, int count) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        List<ConsumerRecord<String, byte[]>> found = List.of();
        while (System.currentTimeMillis() < deadline) {
            found = readDeadLetters().stream()
                    .filter(r -> new String(r.value(), StandardCharsets.ISO_8859_1).contains(marker)).toList();
            if (found.size() >= count) {
                break;
            }
            Thread.sleep(500);
        }
        return found;
    }

    // Todo el DLT desde el principio (es de toda la suite)
    private static List<ConsumerRecord<String, byte[]>> readDeadLetters() {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config)) {
            List<TopicPartition> partitions = consumer.partitionsFor(TransferTopics.DEAD_LETTER_TOPIC).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            for (int empty = 0; empty < 3; ) {
                var batch = consumer.poll(Duration.ofMillis(500));
                if (batch.isEmpty()) {
                    empty++;
                }
                batch.forEach(records::add);
            }
        }
        return records;
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static boolean await(Duration max, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + max.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(250);
        }
        return condition.getAsBoolean();
    }
}
