package com.alejandro.bank.fraud.review;

import com.alejandro.bank.contracts.fraud.v1.FraudRule;
import com.alejandro.bank.contracts.fraud.v1.FraudSuspected;
import com.alejandro.bank.contracts.fraud.v1.FraudTopics;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import com.alejandro.bank.fraud.rules.ReviewedTransfer;
import com.alejandro.bank.fraud.rules.SuspicionRule;
import com.google.protobuf.Timestamp;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

// El aviso de una transferencia sospechosa: FraudSuspected en bank.fraud.v1 (clave = cuenta origen). Se publica
// dentro de la transacción del consumidor y se ESPERA la confirmación de Kafka: si Kafka falla, la revisión y el
// reclamo se deshacen y el evento va a la DLT, en vez de quedar revisado sin aviso. El caso contrario (Kafka confirmó
// y la transacción no llegó a confirmar) puede repetir el aviso: por eso su id sale del evento de la transferencia y
// es siempre el mismo, y quien lo consuma lo reconoce como repetido
@ApplicationScoped
public class FraudAlerts {

    private final MutinyEmitter<byte[]> emitter;

    FraudAlerts(@Channel("fraud-alerts") MutinyEmitter<byte[]> emitter) {
        this.emitter = emitter;
    }

    public void suspected(UUID transferEventId, ReviewedTransfer transfer, Set<SuspicionRule> rules) {
        String alertId = alertId(transferEventId).toString();
        Instant now = Instant.now();
        FraudSuspected.Builder event = FraudSuspected.newBuilder()
                .setEventId(alertId)
                .setTransferEventId(transferEventId.toString())
                .setTransferId(transfer.transferId())
                .setFromAccountId(transfer.fromAccountId())
                .setToAccountId(transfer.toAccountId())
                .setAmount(transfer.amount().toPlainString())
                .setCurrency(transfer.currency())
                .setDetectedAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()));
        rules.forEach(rule -> event.addRules(FraudRule.valueOf("FRAUD_RULE_" + rule.name())));
        RecordHeaders headers = new RecordHeaders();
        headers.add(TransferTopics.HEADER_EVENT_TYPE, FraudTopics.FRAUD_SUSPECTED.getBytes(StandardCharsets.UTF_8));
        headers.add(TransferTopics.HEADER_EVENT_ID, alertId.getBytes(StandardCharsets.UTF_8));
        emitter.sendMessageAndAwait(Message.of(event.build().toByteArray())
                .addMetadata(OutgoingKafkaRecordMetadata.<String>builder()
                        .withKey(transfer.fromAccountId())
                        .withHeaders(headers)
                        .build()));
    }

    // El mismo evento de transferencia da siempre el mismo id de aviso (UUID tipo 3, por nombre)
    static UUID alertId(UUID transferEventId) {
        return UUID.nameUUIDFromBytes((FraudTopics.FRAUD_SUSPECTED + ":" + transferEventId)
                .getBytes(StandardCharsets.UTF_8));
    }
}
