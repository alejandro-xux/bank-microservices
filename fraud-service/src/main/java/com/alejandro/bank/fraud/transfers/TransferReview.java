package com.alejandro.bank.fraud.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

// La revisión de una transferencia completada. En BANK-107 solo deja constancia; las reglas de fraude y el aviso de
// una sospechosa llegan en BANK-108
@ApplicationScoped
public class TransferReview {

    private static final Logger LOG = Logger.getLogger(TransferReview.class);

    public void review(TransferCompleted event) {
        LOG.infof("Transferencia %s revisada: %s %s desde la cuenta %s", event.getTransferId(), event.getAmount(),
                event.getCurrency(), event.getFromAccountId());
    }
}
