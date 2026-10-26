package com.alejandro.bank.fraud.rules;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

// Los umbrales de las reglas. Los montos van por moneda: 3000 soles y 3000 dólares no son lo mismo. Una moneda sin
// umbral no dispara la regla de monto (mejor no avisar que avisar con un número pensado para otra moneda).
// Los valores de cada entorno llegan por configuración (fraud.rules.* en application.properties y config-repo)
public record FraudPolicy(
        // HIGH_AMOUNT, por umbral: desde este monto, siempre
        Map<String, BigDecimal> highAmount,
        // HIGH_AMOUNT, por promedio: desde este monto, si supera `averageMultiplier` veces el promedio de la cuenta y
        // la cuenta tiene al menos `averageMinHistory` transferencias. El piso evita avisar por montos chicos (de 1 a
        // 10 soles, el promedio de una cuenta de pruebas hace saltar cualquier cosa)
        Map<String, BigDecimal> averageFloor,
        BigDecimal averageMultiplier,
        int averageMinHistory,
        // VELOCITY: más de `velocityMax` transferencias desde la cuenta en `velocityWindow`, contando esta
        int velocityMax,
        Duration velocityWindow,
        // NEW_DESTINATION_HIGH_AMOUNT: desde este monto, a un destino nuevo para la cuenta
        Map<String, BigDecimal> newDestinationAmount) {

    public FraudPolicy {
        highAmount = Map.copyOf(highAmount);
        averageFloor = Map.copyOf(averageFloor);
        newDestinationAmount = Map.copyOf(newDestinationAmount);
    }
}
