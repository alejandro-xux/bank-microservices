package com.alejandro.bank.notifications.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;

// Bytes del topic → TransferCompleted (contracts/transfer_events.proto). Va dentro de ErrorHandlingDeserializer
// (config-repo/notifications-service.yml): un mensaje ilegible no tira al consumidor, llega al manejo de
// errores (BANK-73). Ojo: proto3 lee cero bytes sin error; validar los campos es tarea del que lo usa (BANK-72)
public class TransferCompletedDeserializer implements Deserializer<TransferCompleted> {

    @Override
    public TransferCompleted deserialize(String topic, byte[] data) {
        try {
            return TransferCompleted.parseFrom(data);
        } catch (InvalidProtocolBufferException ex) {
            throw new SerializationException("No es un TransferCompleted legible", ex);
        }
    }
}
