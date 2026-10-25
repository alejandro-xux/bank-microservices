package com.alejandro.bank.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Prueba rota A PROPÓSITO (prueba al revés de BANK-83): este PR no se tiene que poder mergear. Se cierra sin merge
class CiBlocksBrokenTest {

    @Test
    void thisTestIsBrokenOnPurpose() {
        assertThat(1 + 1).as("prueba rota a propósito").isEqualTo(3);
    }
}
