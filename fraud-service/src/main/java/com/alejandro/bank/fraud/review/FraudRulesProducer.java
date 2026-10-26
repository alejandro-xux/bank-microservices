package com.alejandro.bank.fraud.review;

import com.alejandro.bank.fraud.rules.FraudRules;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

// FraudRules es del dominio y no lleva anotaciones de CDI: se arma aquí con los umbrales de la configuración.
// @Singleton y no @ApplicationScoped: es una clase final sin estado, no necesita proxy
public class FraudRulesProducer {

    @Produces
    @Singleton
    FraudRules fraudRules(FraudRulesConfig config) {
        return new FraudRules(config.policy());
    }
}
