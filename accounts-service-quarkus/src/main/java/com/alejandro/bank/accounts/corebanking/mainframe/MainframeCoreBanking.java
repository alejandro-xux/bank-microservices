package com.alejandro.bank.accounts.corebanking.mainframe;

import com.alejandro.bank.accounts.corebanking.CoreBanking;
import com.alejandro.bank.accounts.corebanking.CoreBankingEntry;
import com.alejandro.bank.accounts.corebanking.PostingResult;
import org.jboss.logging.Logger;

import java.util.function.UnaryOperator;

// El Anti-Corruption Layer (BANK-77), el mismo que en accounts-service: nada de lo que pase afuera se convierte en una
// excepción para el dominio. Timeout, conexión rechazada, 5xx o una respuesta ilegible → UNKNOWN (S6)
public class MainframeCoreBanking implements CoreBanking {

    private static final Logger LOG = Logger.getLogger(MainframeCoreBanking.class);

    private final CoreBankingApi api;

    public MainframeCoreBanking(CoreBankingApi api) {
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

    private static PostingResult call(String operation, CoreBankingEntry entry, String frame, UnaryOperator<String> send) {
        try {
            PostingResult result = CoreBankingFrames.reply(send.apply(frame), operation, entry.operationNumber());
            if (result.outcome() == PostingResult.Outcome.UNKNOWN) {
                LOG.warnf("Respuesta del core que no se entiende para %s %d: %s", operation, entry.operationNumber(), result.code());
            }
            return result;
        } catch (RuntimeException ex) {
            LOG.warnf("Sin respuesta del core para %s %d: %s", operation, entry.operationNumber(), ex.getMessage());
            return PostingResult.unknown(ex.getClass().getSimpleName());
        }
    }
}
