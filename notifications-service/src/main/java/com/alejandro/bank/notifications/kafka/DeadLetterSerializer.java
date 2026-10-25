package com.alejandro.bank.notifications.kafka;

import com.google.protobuf.MessageLite;
import org.apache.kafka.common.serialization.Serializer;

// Lo que se manda al DLT (BANK-73): un evento que se leyó bien pero falló (TransferCompleted → sus bytes) o los
// bytes originales de uno que no se pudo leer (DeadLetterPublishingRecoverer los reenvía tal cual)
public class DeadLetterSerializer implements Serializer<Object> {

    @Override
    public byte[] serialize(String topic, Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof byte[] raw) {
            return raw;
        }
        if (data instanceof MessageLite message) {
            return message.toByteArray();
        }
        throw new IllegalArgumentException("No se sabe mandar al DLT un " + data.getClass().getName());
    }
}
