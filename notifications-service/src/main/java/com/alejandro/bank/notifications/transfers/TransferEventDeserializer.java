package com.alejandro.bank.notifications.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.contracts.transfers.v1.TransferFailed;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;

import java.nio.charset.StandardCharsets;

// Bytes del topic → el mensaje de contracts/transfer_events.proto que dice el header event-type (BANK-80; antes solo
// TransferCompleted, BANK-70). El tipo se decide por el header y NUNCA probando: los números de campo de los dos
// mensajes se pisan, y un TransferFailed leído como TransferCompleted puede salir legible con los datos corridos.
// Un tipo que este servicio no conoce (o sin header) no es un error: es un evento para otro consumidor, y se devuelve
// como UnknownTransferEvent para que el listener lo salte. Va dentro de ErrorHandlingDeserializer
// (config-repo/notifications-service.yml): unos bytes ilegibles no tiran al consumidor, van al DLT (BANK-73)
public class TransferEventDeserializer implements Deserializer<Object> {

    // Un evento de un tipo que este servicio no maneja
    public record UnknownTransferEvent(String eventType) {
    }

    @Override
    public Object deserialize(String topic, byte[] data) {
        throw new UnsupportedOperationException("El tipo de evento viene en los headers");
    }

    @Override
    public Object deserialize(String topic, Headers headers, byte[] data) {
        String eventType = eventType(headers);
        try {
            return switch (eventType) {
                case TransferTopics.TRANSFER_COMPLETED -> TransferCompleted.parseFrom(data);
                case TransferTopics.TRANSFER_FAILED -> TransferFailed.parseFrom(data);
                default -> new UnknownTransferEvent(eventType);
            };
        } catch (InvalidProtocolBufferException ex) {
            throw new SerializationException("No es un " + eventType + " legible", ex);
        }
    }

    private static String eventType(Headers headers) {
        Header header = headers.lastHeader(TransferTopics.HEADER_EVENT_TYPE);
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }
}
