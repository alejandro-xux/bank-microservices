package com.alejandro.bank.notifications.transfers;

// El evento se pudo leer pero no sirve (por ejemplo, un Protobuf vacío: proto3 lo lee sin error). Reintentar no lo
// arregla: va directo al DLT (BANK-73)
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
