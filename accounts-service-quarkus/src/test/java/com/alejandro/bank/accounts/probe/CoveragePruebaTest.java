package com.alejandro.bank.accounts.probe;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// TEMPORAL (prueba al revés de BANK-106)
class CoveragePruebaTest {

    @Test
    void classifiesAndCaps() {
        assertThat(CoveragePrueba.classify(5)).isEqualTo("positivo");
        assertThat(CoveragePrueba.capped(1, 2)).isEqualTo(3);
    }
}
