package com.alejandro.bank.accounts.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountNumberTest {

    @Test
    void luhnCheckDigitMatchesTheReferenceExample() {
        // Ejemplo clásico de Luhn: 7992739871 → dígito 3 (79927398713 es válido)
        assertThat(AccountNumber.luhnCheckDigit("7992739871")).isEqualTo(3);
    }

    @Test
    void numberHasBranchSerialAndCheckDigit() {
        assertThat(AccountNumber.of(42)).matches("001-0000000042-\\d");
        assertThat(AccountNumber.of(9_999_999_999L)).startsWith("001-9999999999-");
    }

    @Test
    void checkDigitMakesTheWholeNumberValid() {
        String digits = AccountNumber.of(42).replace("-", "");

        assertThat(isValidLuhn(digits)).isTrue();
    }

    @Test
    void changingOneDigitIsDetected() {
        String digits = AccountNumber.of(42).replace("-", "");
        String typo = digits.substring(0, 12) + "3" + digits.substring(13);

        assertThat(isValidLuhn(typo)).isFalse();
    }

    @Test
    void sequenceOutOfRangeIsRejected() {
        assertThatThrownBy(() -> AccountNumber.of(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountNumber.of(10_000_000_000L)).isInstanceOf(IllegalArgumentException.class);
    }

    // Un número completo es válido si su último dígito es el verificador de los anteriores
    private static boolean isValidLuhn(String digits) {
        int last = digits.length() - 1;
        return AccountNumber.luhnCheckDigit(digits.substring(0, last)) == digits.charAt(last) - '0';
    }
}
