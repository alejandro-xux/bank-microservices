package com.alejandro.bank.accounts.outbox;

import com.alejandro.bank.accounts.domain.Account;
import com.alejandro.bank.accounts.domain.Transfer;
import com.alejandro.bank.contracts.transfers.v1.TransferFailed;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import com.google.protobuf.Timestamp;

import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

// De una transferencia rechazada por el core y ya compensada al mensaje del outbox con su evento TransferFailed
// (BANK-80). Solo el lado del que envía: el que iba a recibir nunca recibió nada
public final class TransferFailedEvents {

    private TransferFailedEvents() {
    }

    public static OutboxStore.Message of(Transfer transfer, Account from, String reason, Instant failedAt) {
        UUID eventId = UUID.randomUUID();
        TransferFailed event = TransferFailed.newBuilder()
                .setEventId(eventId.toString())
                .setTransferId(transfer.getId().toString())
                .setFromAccountId(from.getId().toString())
                .setFromAccountNumber(from.getNumber())
                .setFromCustomerId(from.getCustomerId())
                .setAmount(transfer.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString())
                .setCurrency(transfer.getCurrency().name())
                .setReason(reason)
                .setFailedAt(Timestamp.newBuilder()
                        .setSeconds(failedAt.getEpochSecond())
                        .setNanos(failedAt.getNano()))
                .build();
        // Misma clave que TransferCompleted (la cuenta origen): los eventos de una cuenta siguen en orden
        return new OutboxStore.Message(eventId, TransferTopics.TRANSFER_FAILED, from.getId().toString(),
                event.toByteArray());
    }
}
