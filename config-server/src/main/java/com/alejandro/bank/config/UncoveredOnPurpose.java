package com.alejandro.bank.config;

// SIN PRUEBAS A PROPÓSITO (prueba al revés de BANK-84): código nuevo sin cobertura tiene que hacer fallar el Quality
// Gate. Más de 20 líneas, porque con menos SonarCloud no evalúa la cobertura. Este PR se cierra sin merge
final class UncoveredOnPurpose {

    private UncoveredOnPurpose() {
    }

    static String classify(int amountInCents) {
        if (amountInCents < 0) {
            return "negativo";
        }
        if (amountInCents == 0) {
            return "cero";
        }
        if (amountInCents < 100) {
            return "céntimos";
        }
        if (amountInCents < 10_000) {
            return "menor a 100";
        }
        if (amountInCents < 1_000_000) {
            return "menor a 10 000";
        }
        return "grande";
    }

    static int digits(int value) {
        int count = 0;
        int rest = Math.abs(value);
        do {
            count++;
            rest /= 10;
        } while (rest > 0);
        return count;
    }
}
