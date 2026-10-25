package com.alejandro.bank.accounts.outbox;

import com.alejandro.bank.accounts.domain.Account;
import com.alejandro.bank.accounts.domain.Transfer;
import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import com.google.protobuf.Timestamp;

import java.math.RoundingMode;
import java.util.UUID;

// De una transferencia ya registrada al mensaje del outbox con su evento TransferCompleted (BANK-69).
// Solo ids, números de cuenta y montos (contracts/transfer_events.proto): nada de datos personales
public final class TransferCompletedEvents {

    private TransferCompletedEvents() {
    }

    public static OutboxStore.Message of(Transfer transfer, Account from, Account to) {
        UUID eventId = UUID.randomUUID();
        TransferCompleted event = TransferCompleted.newBuilder()
                .setEventId(eventId.toString())
                .setTransferId(transfer.getId().toString())
                .setFromAccountId(from.getId().toString())
                .setFromAccountNumber(from.getNumber())
                .setToAccountId(to.getId().toString())
                .setToAccountNumber(to.getNumber())
                .setFromCustomerId(from.getCustomerId())
                .setToCustomerId(to.getCustomerId())
                // Siempre con 2 decimales, como en la API ("150.00", no "150")
                .setAmount(transfer.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString())
                .setCurrency(transfer.getCurrency().name())
                .setCompletedAt(Timestamp.newBuilder()
                        .setSeconds(transfer.getCreatedAt().getEpochSecond())
                        .setNanos(transfer.getCreatedAt().getNano()))
                .build();
        // Clave = cuenta origen: sus eventos caen en la misma partición y conservan el orden
        return new OutboxStore.Message(eventId, TransferTopics.TRANSFER_COMPLETED, from.getId().toString(),
                event.toByteArray());
    }
}
