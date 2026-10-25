package com.alejandro.bank.accounts.corebanking;

// El core bancario (el "mainframe") visto desde el dominio (BANK-77, spike BANK-74). El dominio solo conoce esta
// interfaz y sus tipos: cómo se habla con el core (tramas de largo fijo, HTTP, MQ) vive en corebanking.mainframe.
// Ninguna llamada lanza excepciones: si el core no contesta algo entendible, el resultado es UNKNOWN (S6)
public interface CoreBanking {

    // Registra el asiento contable de una transferencia. Idempotente por el número de operación: reenviar el mismo
    // asiento no lo registra dos veces
    PostingResult post(CoreBankingEntry entry);

    // Qué pasó con un asiento (para el recuperador, BANK-79). La consulta lleva los datos del asiento original
    PostingResult query(CoreBankingEntry entry);
}
