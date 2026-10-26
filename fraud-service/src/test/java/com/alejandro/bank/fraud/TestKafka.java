package com.alejandro.bank.fraud;

import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.awaitility.Awaitility.await;

// Kafka visto desde fuera de la aplicación, para las pruebas (también contra el binario nativo): publicar en
// bank.transfers.v1 como lo hace el outbox de accounts y leer un topic por clave
public final class TestKafka {

    public static final Duration WAIT = Duration.ofSeconds(30);

    private static KafkaProducer<String, byte[]> producer;

    private TestKafka() {
    }

    // Con el header event-type, como el outbox (BANK-69); type null = sin header
    public static void publish(String key, String type, byte[] value) throws ExecutionException, InterruptedException {
        ProducerRecord<String, byte[]> message = new ProducerRecord<>(TransferTopics.TOPIC, key, value);
        if (type != null) {
            message.headers().add(TransferTopics.HEADER_EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        }
        producer().send(message).get();
    }

    // Lee el topic desde el principio con un grupo nuevo y se queda con los mensajes de esa clave
    public static List<ConsumerRecord<String, byte[]>> read(String topic, String key) {
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudTestResource.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "pruebas-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
            long until = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < until) {
                consumer.poll(Duration.ofMillis(500)).forEach(message -> {
                    if (key.equals(message.key())) {
                        found.add(message);
                    }
                });
            }
            return found;
        }
    }

    // Espera hasta que haya al menos un mensaje de esa clave y devuelve todos los que hay
    public static List<ConsumerRecord<String, byte[]>> awaitAny(String topic, String key) {
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        await().atMost(WAIT).until(() -> {
            found.clear();
            found.addAll(read(topic, key));
            return !found.isEmpty();
        });
        return found;
    }

    public static String header(ConsumerRecord<String, byte[]> message, String name) {
        return new String(message.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    // Se crea al primer uso: así no depende de si Quarkus ya levantó los contenedores
    private static synchronized KafkaProducer<String, byte[]> producer() {
        if (producer == null) {
            producer = new KafkaProducer<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudTestResource.kafkaBootstrapServers(),
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
        }
        return producer;
    }
}
