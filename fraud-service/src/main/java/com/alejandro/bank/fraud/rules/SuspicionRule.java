package com.alejandro.bank.fraud.rules;

// Las reglas de fraude (BANK-108). Del dominio: no dependen de Quarkus, de Kafka ni del contrato (el adaptador las
// traduce a FraudRule de fraud_events.proto al publicar)
public enum SuspicionRule {
    // Monto por encima del umbral de su moneda, o varias veces el promedio de la cuenta
    HIGH_AMOUNT,
    // Más de N transferencias desde la misma cuenta en M minutos
    VELOCITY,
    // Un destino al que la cuenta nunca había transferido, con un monto alto
    NEW_DESTINATION_HIGH_AMOUNT
}
