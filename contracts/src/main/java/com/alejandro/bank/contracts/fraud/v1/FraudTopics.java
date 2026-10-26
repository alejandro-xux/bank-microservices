package com.alejandro.bank.contracts.fraud.v1;

// Lo que acompaña a fraud_events.proto en el cable (BANK-108), como TransferTopics: el topic y el valor del header
// event-type. Los headers son los mismos que en las transferencias (TransferTopics.HEADER_EVENT_TYPE / _ID).
// Los topics los crea infra/kafka/create-topics.sh, no la app
public final class FraudTopics {

    // Transferencias sospechosas. Clave = cuenta origen, como bank.transfers.v1
    public static final String TOPIC = "bank.fraud.v1";
    // Lo que fraud-service no pudo procesar de bank.transfers.v1 (BANK-107)
    public static final String DEAD_LETTER_TOPIC = TOPIC + ".DLT";

    // Valor del header event-type
    public static final String FRAUD_SUSPECTED = "FraudSuspected";

    private FraudTopics() {
    }
}
