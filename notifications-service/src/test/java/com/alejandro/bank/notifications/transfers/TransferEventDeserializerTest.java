package com.alejandro.bank.notifications.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.contracts.transfers.v1.TransferFailed;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// BANK-80: el header event-type decide qué mensaje se lee, nunca el contenido
class TransferEventDeserializerTest {

    private final TransferEventDeserializer deserializer = new TransferEventDeserializer();

    private static final TransferFailed FAILED = TransferFailed.newBuilder()
            .setEventId("5d0f4f5e-3c1b-4a7e-9a10-2b8c7d6e5f40").setFromCustomerId("ana").setAmount("100.13").setReason("057")
            .build();

    @Test
    void eachTypeIsReadAsWhatItsHeaderSays() {
        TransferCompleted completed = TransferCompleted.newBuilder().setEventId("e1").setAmount("10.00").build();

        assertThat(deserializer.deserialize("t", headers(TransferTopics.TRANSFER_COMPLETED), completed.toByteArray()))
                .isEqualTo(completed);
        assertThat(deserializer.deserialize("t", headers(TransferTopics.TRANSFER_FAILED), FAILED.toByteArray()))
                .isEqualTo(FAILED);
    }

    @Test
    void anUnknownTypeIsNotAnErrorItIsAnEventForSomeoneElse() {
        assertThat(deserializer.deserialize("t", headers("TransferScheduled"), new byte[]{1, 2, 3}))
                .isEqualTo(new TransferEventDeserializer.UnknownTransferEvent("TransferScheduled"));
    }

    // BANK-111: sin header no es "un evento de otro", es un mensaje roto
    @Test
    void withoutHeaderItIsABrokenMessage() {
        assertThatThrownBy(() -> deserializer.deserialize("t", new RecordHeaders(), FAILED.toByteArray()))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("sin header event-type");
    }

    @Test
    void unreadableBytesOfAKnownTypeAreAnErrorSoTheyGoToTheDeadLetterTopic() {
        assertThatThrownBy(() -> deserializer.deserialize("t", headers(TransferTopics.TRANSFER_FAILED),
                "no es protobuf".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("TransferFailed");
    }

    private static RecordHeaders headers(String eventType) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(TransferTopics.HEADER_EVENT_TYPE, eventType.getBytes(StandardCharsets.UTF_8));
        return headers;
    }
}
