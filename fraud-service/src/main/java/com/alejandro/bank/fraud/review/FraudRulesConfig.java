package com.alejandro.bank.fraud.review;

import com.alejandro.bank.fraud.rules.FraudPolicy;
import io.smallrye.config.ConfigMapping;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

// Los umbrales de las reglas (fraud.rules.*), con los valores por defecto en application.properties y lo propio de
// cada entorno en config-repo/fraud-service.yml. Se leen al arrancar: un umbral nuevo pide reiniciar el servicio
@ConfigMapping(prefix = "fraud.rules")
public interface FraudRulesConfig {

    Map<String, BigDecimal> highAmount();

    Map<String, BigDecimal> averageFloor();

    BigDecimal averageMultiplier();

    int averageMinHistory();

    int velocityMax();

    Duration velocityWindow();

    Map<String, BigDecimal> newDestinationAmount();

    default FraudPolicy policy() {
        return new FraudPolicy(highAmount(), averageFloor(), averageMultiplier(), averageMinHistory(), velocityMax(),
                velocityWindow(), newDestinationAmount());
    }
}
