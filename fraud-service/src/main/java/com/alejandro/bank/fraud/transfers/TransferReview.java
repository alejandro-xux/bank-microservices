package com.alejandro.bank.fraud.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.fraud.review.FraudAlerts;
import com.alejandro.bank.fraud.review.FraudReviews;
import com.alejandro.bank.fraud.review.FraudRulesConfig;
import com.alejandro.bank.fraud.rules.AccountHistory;
import com.alejandro.bank.fraud.rules.FraudRules;
import com.alejandro.bank.fraud.rules.ReviewedTransfer;
import com.alejandro.bank.fraud.rules.SuspicionRule;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

// La revisión de una transferencia completada (BANK-108): la historia de la cuenta, las reglas, el registro de la
// evaluación y, si alguna regla disparó, el aviso. Todo dentro de la transacción del consumidor, después del reclamo
// del evento: un evento repetido no llega hasta aquí
@ApplicationScoped
public class TransferReview {

    private static final Logger LOG = Logger.getLogger(TransferReview.class);

    private final FraudRules rules;
    private final FraudRulesConfig config;
    private final FraudReviews reviews;
    private final FraudAlerts alerts;

    TransferReview(FraudRules rules, FraudRulesConfig config, FraudReviews reviews, FraudAlerts alerts) {
        this.rules = rules;
        this.config = config;
        this.reviews = reviews;
        this.alerts = alerts;
    }

    public void review(UUID eventId, TransferCompleted event) {
        ReviewedTransfer transfer = new ReviewedTransfer(event.getTransferId(), event.getFromAccountId(),
                event.getToAccountId(), new BigDecimal(event.getAmount()), event.getCurrency(),
                Instant.ofEpochSecond(event.getCompletedAt().getSeconds(), event.getCompletedAt().getNanos()));
        AccountHistory history = reviews.historyBefore(transfer, config.velocityWindow());
        Set<SuspicionRule> hits = rules.evaluate(transfer, history);
        reviews.record(eventId, transfer, hits);
        if (hits.isEmpty()) {
            LOG.debugf("Transferencia %s revisada: sin reglas disparadas", transfer.transferId());
            return;
        }
        alerts.suspected(eventId, transfer, hits);
        // Solo ids y reglas en el log, sin datos personales
        LOG.warnf("Transferencia %s sospechosa (%s): %s %s desde la cuenta %s", transfer.transferId(), hits,
                transfer.amount(), transfer.currency(), transfer.fromAccountId());
    }
}
