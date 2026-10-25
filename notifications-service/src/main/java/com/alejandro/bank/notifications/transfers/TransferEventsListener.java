package com.alejandro.bank.notifications.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// Consume bank.transfers.v1 con el consumer group notifications-service (BANK-70). Cada grupo lleva su propio
// offset: fraud-service podrá leer los mismos eventos con su grupo sin afectar a este
@Component
@RequiredArgsConstructor
public class TransferEventsListener {

    // Nombre del contenedor del listener (para pausarlo o reanudarlo, BANK-73)
    public static final String ID = "transfers";

    private final TransferNotifier notifier;

    // idIsGroup = false: si no, Spring Kafka usa el id ("transfers") como consumer group en lugar del group-id
    // de la configuración, y dos servicios con el mismo id compartirían el offset (se repartirían los mensajes)
    @KafkaListener(id = ID, idIsGroup = false, topics = TransferTopics.TOPIC)
    void on(TransferCompleted event) {
        notifier.notify(event);
    }
}
