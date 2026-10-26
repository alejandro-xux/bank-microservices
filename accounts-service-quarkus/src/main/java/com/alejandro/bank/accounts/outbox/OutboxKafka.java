package com.alejandro.bank.accounts.outbox;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.Map;

// El productor del outbox con el cliente de Kafka directo (D5 del Sprint 12), con la misma configuración que
// spring.kafka.producer de accounts-service. Se crea la primera vez que el relay publica
class OutboxKafka {

    @Produces
    @ApplicationScoped
    Producer<String, byte[]> producer(@ConfigProperty(name = "bank.kafka.bootstrap-servers") String bootstrapServers) {
        return new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                // El payload ya viene en Protobuf desde el outbox: viaja tal cual
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                // El relay marca un evento como publicado solo cuando Kafka confirmó que lo guardó
                ProducerConfig.ACKS_CONFIG, "all",
                // Con Kafka caído, los valores por defecto dejan al relay hasta 60 s con una conexión de PostgreSQL
                // tomada; así falla a los 5-10 s y reintenta en la siguiente vuelta (spike BANK-66, K6)
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000));
    }

    void close(@Disposes Producer<String, byte[]> producer) {
        producer.close(Duration.ofSeconds(5));
    }
}
