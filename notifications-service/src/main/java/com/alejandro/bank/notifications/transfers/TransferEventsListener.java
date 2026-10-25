package com.alejandro.bank.notifications.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.contracts.transfers.v1.TransferFailed;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// Consume bank.transfers.v1 con el consumer group notifications-service (BANK-70). Cada grupo lleva su propio
// offset: fraud-service podrá leer los mismos eventos con su grupo sin afectar a este.
// En la clase y no en un método (BANK-80): TransferEventDeserializer ya eligió el mensaje por el header event-type,
// y Spring Kafka llama al @KafkaHandler del tipo que llegó
// idIsGroup = false: si no, Spring Kafka usa el id ("transfers") como consumer group en lugar del group-id de la
// configuración, y dos servicios con el mismo id compartirían el offset (se repartirían los mensajes)
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaListener(id = TransferEventsListener.ID, idIsGroup = false, topics = TransferTopics.TOPIC)
public class TransferEventsListener {

    // Nombre del contenedor del listener (para pausarlo o reanudarlo, BANK-73)
    public static final String ID = "transfers";

    private final TransferNotifier notifier;

    @KafkaHandler
    void on(TransferCompleted event) {
        notifier.notify(event);
    }

    @KafkaHandler
    void on(TransferFailed event) {
        notifier.notify(event);
    }

    // Un tipo de evento que este servicio no maneja (uno nuevo de accounts, para otro consumidor): se salta y el
    // offset avanza. No es un error ni va al DLT, porque no hay nada roto
    @KafkaHandler(isDefault = true)
    void ignore(Object event) {
        log.info("Evento de transferencia ignorado: {}", event);
    }
}
