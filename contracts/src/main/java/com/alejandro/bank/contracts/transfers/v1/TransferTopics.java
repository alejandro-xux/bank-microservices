package com.alejandro.bank.contracts.transfers.v1;

// Lo que acompaña a transfer_events.proto en el cable (BANK-68): el topic y los headers de cada mensaje.
// (No se llama TransferEvents: ese nombre lo usa protoc para la clase que genera del archivo .proto.)
// Productor y consumidor usan estas constantes, así un nombre mal escrito no compila (con la creación
// automática apagada, un topic mal escrito fallaría recién al publicar, BANK-67).
// Los topics los crea infra/kafka/create-topics.sh, no la app
public final class TransferTopics {

    public static final String TOPIC = "bank.transfers.v1";
    // Mensajes que nunca se van a poder procesar (BANK-73)
    public static final String DEAD_LETTER_TOPIC = TOPIC + ".DLT";

    // Headers: qué evento es y su id, para leerlos sin abrir el payload
    public static final String HEADER_EVENT_TYPE = "event-type";
    public static final String HEADER_EVENT_ID = "event-id";

    // Valores del header event-type: dicen qué mensaje de transfer_events.proto hay en el payload
    public static final String TRANSFER_COMPLETED = "TransferCompleted";
    // BANK-80
    public static final String TRANSFER_FAILED = "TransferFailed";

    private TransferTopics() {
    }
}
