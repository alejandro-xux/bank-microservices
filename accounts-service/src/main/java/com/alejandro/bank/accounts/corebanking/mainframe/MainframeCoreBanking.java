package com.alejandro.bank.accounts.corebanking.mainframe;

import com.alejandro.bank.accounts.corebanking.CoreBanking;
import com.alejandro.bank.accounts.corebanking.CoreBankingEntry;
import com.alejandro.bank.accounts.corebanking.PostingResult;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Function;

// El Anti-Corruption Layer (BANK-77): traduce entre el dominio y el core. Nada de lo que pase afuera se convierte
// en una excepción para el dominio: timeout, conexión rechazada, 5xx, un 404 por una trama que el core no reconoce
// o una respuesta ilegible → UNKNOWN (S6). Un rechazo solo existe si el core lo dijo con una trama válida
@Slf4j
class MainframeCoreBanking implements CoreBanking {

    private final CoreBankingApi api;

    MainframeCoreBanking(CoreBankingApi api) {
        this.api = api;
    }

    @Override
    public PostingResult post(CoreBankingEntry entry) {
        return call("TRX", entry, CoreBankingFrames.posting(entry), api::post);
    }

    @Override
    public PostingResult query(CoreBankingEntry entry) {
        return call("QRY", entry, CoreBankingFrames.query(entry), api::query);
    }

    private static PostingResult call(String operation, CoreBankingEntry entry, String frame, Function<String, String> send) {
        try {
            PostingResult result = CoreBankingFrames.reply(send.apply(frame), operation, entry.operationNumber());
            if (result.outcome() == PostingResult.Outcome.UNKNOWN) {
                log.warn("Respuesta del core que no se entiende para {} {}: {}", operation, entry.operationNumber(), result.code());
            }
            return result;
        } catch (RuntimeException ex) {
            log.warn("Sin respuesta del core para {} {}: {}", operation, entry.operationNumber(), ex.getMessage());
            return PostingResult.unknown(ex.getClass().getSimpleName());
        }
    }
}
