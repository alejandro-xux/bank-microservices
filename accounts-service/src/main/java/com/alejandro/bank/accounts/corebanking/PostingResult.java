package com.alejandro.bank.accounts.corebanking;

// Lo que respondió el core a un asiento o a una consulta (BANK-77, spike BANK-74 S6)
public record PostingResult(Outcome outcome, String code) {

    public enum Outcome {
        // El core registró el asiento
        POSTED,
        // El core lo rechazó (code dice por qué): no se registró
        REJECTED,
        // Consulta: el core nunca lo registró
        NOT_FOUND,
        // No sabemos: timeout, conexión rechazada, error del core o una respuesta que no se entiende. NUNCA es un
        // rechazo: el core pudo haberlo registrado aunque la respuesta no llegara
        UNKNOWN
    }

    public static PostingResult unknown(String why) {
        return new PostingResult(Outcome.UNKNOWN, why);
    }
}
