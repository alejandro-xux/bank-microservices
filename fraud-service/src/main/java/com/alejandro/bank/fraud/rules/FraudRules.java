package com.alejandro.bank.fraud.rules;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

// Las reglas de fraude sobre una transferencia completada (BANK-108): simples y explicables, cada una dice por qué
// disparó. Dominio puro: recibe la transferencia y lo que se sabe de la cuenta (AccountHistory) y no lee nada más.
// Sin reglas disparadas, la transferencia es normal
public final class FraudRules {

    private final FraudPolicy policy;

    public FraudRules(FraudPolicy policy) {
        this.policy = policy;
    }

    public Set<SuspicionRule> evaluate(ReviewedTransfer transfer, AccountHistory history) {
        Set<SuspicionRule> hits = EnumSet.noneOf(SuspicionRule.class);
        if (highAmount(transfer, history)) {
            hits.add(SuspicionRule.HIGH_AMOUNT);
        }
        // Las de la ventana más esta
        if (history.recentTransfers() + 1 > policy.velocityMax()) {
            hits.add(SuspicionRule.VELOCITY);
        }
        if (!history.knownDestination() && atLeast(transfer, policy.newDestinationAmount().get(transfer.currency()))) {
            hits.add(SuspicionRule.NEW_DESTINATION_HIGH_AMOUNT);
        }
        return hits;
    }

    private boolean highAmount(ReviewedTransfer transfer, AccountHistory history) {
        if (atLeast(transfer, policy.highAmount().get(transfer.currency()))) {
            return true;
        }
        return history.previousTransfers() >= policy.averageMinHistory()
                && history.averageAmount() != null
                && atLeast(transfer, policy.averageFloor().get(transfer.currency()))
                && transfer.amount().compareTo(history.averageAmount().multiply(policy.averageMultiplier())) > 0;
    }

    // Sin umbral para la moneda, la regla no dispara
    private static boolean atLeast(ReviewedTransfer transfer, BigDecimal threshold) {
        return threshold != null && transfer.amount().compareTo(threshold) >= 0;
    }
}
