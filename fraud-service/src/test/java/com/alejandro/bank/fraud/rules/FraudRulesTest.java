package com.alejandro.bank.fraud.rules;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Las reglas solas, sin Quarkus: cada una con sus bordes. Los umbrales son los de application.properties
class FraudRulesTest {

    private static final FraudPolicy POLICY = new FraudPolicy(
            Map.of("PEN", new BigDecimal("10000.00"), "USD", new BigDecimal("3000.00")),
            Map.of("PEN", new BigDecimal("1000.00"), "USD", new BigDecimal("300.00")),
            new BigDecimal("5"), 3,
            5, Duration.ofMinutes(10),
            Map.of("PEN", new BigDecimal("2000.00"), "USD", new BigDecimal("600.00")));

    private final FraudRules rules = new FraudRules(POLICY);

    // Una cuenta con historia normal: 2 transferencias recientes, 10 antes con promedio de 100 y el destino conocido
    private static final AccountHistory USUAL = new AccountHistory(2, 10, new BigDecimal("100.00"), true);

    @Test
    void anOrdinaryTransferTriggersNothing() {
        assertThat(rules.evaluate(transfer("250.00", "PEN"), USUAL)).isEmpty();
    }

    @Test
    void theAmountThresholdIsInclusiveAndPerCurrency() {
        assertThat(rules.evaluate(transfer("9999.99", "PEN"), known(0))).isEmpty();
        assertThat(rules.evaluate(transfer("10000.00", "PEN"), known(0))).containsExactly(SuspicionRule.HIGH_AMOUNT);
        // 3000 dólares sí; 3000 soles no
        assertThat(rules.evaluate(transfer("3000.00", "USD"), known(0))).containsExactly(SuspicionRule.HIGH_AMOUNT);
        assertThat(rules.evaluate(transfer("3000.00", "PEN"), known(0))).isEmpty();
    }

    @Test
    void aCurrencyWithoutThresholdsNeverTriggersTheAmountRules() {
        assertThat(rules.evaluate(transfer("1000000.00", "EUR"), AccountHistory.none())).isEmpty();
    }

    @Test
    void manyTimesTheAverageIsHighAboveTheFloor() {
        AccountHistory average200 = new AccountHistory(0, 3, new BigDecimal("200.00"), true);
        // 5 × 200 = 1000: tiene que pasarlo, no igualarlo
        assertThat(rules.evaluate(transfer("1000.00", "PEN"), average200)).isEmpty();
        assertThat(rules.evaluate(transfer("1000.01", "PEN"), average200)).containsExactly(SuspicionRule.HIGH_AMOUNT);
    }

    @Test
    void belowTheFloorTheAverageDoesNotCount() {
        // 50 veces el promedio, pero 500 soles no es un monto que merezca un aviso
        AccountHistory tiny = new AccountHistory(0, 20, new BigDecimal("10.00"), true);
        assertThat(rules.evaluate(transfer("500.00", "PEN"), tiny)).isEmpty();
    }

    @Test
    void theAverageNeedsEnoughHistory() {
        AccountHistory twoBefore = new AccountHistory(0, 2, new BigDecimal("100.00"), true);
        assertThat(rules.evaluate(transfer("1500.00", "PEN"), twoBefore)).isEmpty();
        AccountHistory threeBefore = new AccountHistory(0, 3, new BigDecimal("100.00"), true);
        assertThat(rules.evaluate(transfer("1500.00", "PEN"), threeBefore)).containsExactly(SuspicionRule.HIGH_AMOUNT);
    }

    @Test
    void theSixthTransferInTheWindowTriggersVelocity() {
        // 4 antes + esta = 5: todavía no
        assertThat(rules.evaluate(transfer("10.00", "PEN"), known(4))).isEmpty();
        // 5 antes + esta = 6: más de 5
        assertThat(rules.evaluate(transfer("10.00", "PEN"), known(5))).containsExactly(SuspicionRule.VELOCITY);
    }

    @Test
    void aNewDestinationOnlyMattersWithAHighAmount() {
        AccountHistory newDestination = new AccountHistory(0, 10, new BigDecimal("1500.00"), false);
        assertThat(rules.evaluate(transfer("1999.99", "PEN"), newDestination)).isEmpty();
        assertThat(rules.evaluate(transfer("2000.00", "PEN"), newDestination))
                .containsExactly(SuspicionRule.NEW_DESTINATION_HIGH_AMOUNT);
        // La primera transferencia de una cuenta va siempre a un destino nuevo
        assertThat(rules.evaluate(transfer("600.00", "USD"), AccountHistory.none()))
                .containsExactly(SuspicionRule.NEW_DESTINATION_HIGH_AMOUNT);
    }

    @Test
    void severalRulesCanTriggerTogether() {
        AccountHistory burstToNewDestination = new AccountHistory(7, 3, new BigDecimal("50.00"), false);
        assertThat(rules.evaluate(transfer("12000.00", "PEN"), burstToNewDestination)).containsExactly(
                SuspicionRule.HIGH_AMOUNT, SuspicionRule.VELOCITY, SuspicionRule.NEW_DESTINATION_HIGH_AMOUNT);
    }

    private static AccountHistory known(int recentTransfers) {
        return new AccountHistory(recentTransfers, 0, null, true);
    }

    private static ReviewedTransfer transfer(String amount, String currency) {
        return new ReviewedTransfer("t-1", "cuenta-origen", "cuenta-destino", new BigDecimal(amount), currency,
                Instant.parse("2026-10-26T15:00:00Z"));
    }
}
