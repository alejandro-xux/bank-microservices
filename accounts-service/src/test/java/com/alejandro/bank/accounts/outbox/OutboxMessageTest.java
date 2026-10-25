package com.alejandro.bank.accounts.outbox;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// BANK-84 (hallazgo de SonarCloud): un mensaje del outbox se compara por el contenido del evento, no por la referencia
// del arreglo, y su toString no muestra los bytes del evento
class OutboxMessageTest {

    private static final UUID ID = UUID.fromString("5d0f4f5e-3c1b-4a7e-9a10-2b8c7d6e5f40");

    @Test
    void twoMessagesWithTheSameBytesAreEqual() {
        OutboxStore.Message one = new OutboxStore.Message(ID, "TransferCompleted", "cuenta", new byte[]{1, 2, 3});
        OutboxStore.Message other = new OutboxStore.Message(ID, "TransferCompleted", "cuenta", new byte[]{1, 2, 3});

        assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
        assertThat(one).isNotEqualTo(new OutboxStore.Message(ID, "TransferCompleted", "cuenta", new byte[]{1, 2, 4}));
    }

    @Test
    void toStringShowsTheSizeNotTheEvent() {
        OutboxStore.Message message = new OutboxStore.Message(ID, "TransferFailed", "cuenta", new byte[]{65, 110, 97});

        assertThat(message.toString()).contains("payload=3 bytes").doesNotContain("[65, 110, 97]").doesNotContain("Ana");
    }
}
