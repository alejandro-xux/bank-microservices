package com.alejandro.bank.accounts.domain;

// Número de cuenta 001-NNNNNNNNNN-D (spike BANK-51): oficina, correlativo de la secuencia
// account_number_seq y dígito verificador Luhn sobre los 13 dígitos anteriores.
// El dígito detecta un número mal tipeado: cambiar cualquier dígito lo vuelve inválido
public final class AccountNumber {

    static final String BRANCH = "001";
    private static final long MAX_SEQUENCE = 9_999_999_999L;

    private AccountNumber() {
    }

    public static String of(long sequence) {
        if (sequence < 1 || sequence > MAX_SEQUENCE) {
            throw new IllegalArgumentException("Correlativo fuera de rango: " + sequence);
        }
        String serial = String.format("%010d", sequence);
        return BRANCH + "-" + serial + "-" + luhnCheckDigit(BRANCH + serial);
    }

    // Formato 001-NNNNNNNNNN-D con el dígito verificador correcto (detecta un número mal tipeado)
    public static boolean isValid(String number) {
        if (number == null || !number.matches("\\d{3}-\\d{10}-\\d")) {
            return false;
        }
        String digits = number.replace("-", "");
        return luhnCheckDigit(digits.substring(0, 13)) == digits.charAt(13) - '0';
    }

    // Recorre de derecha a izquierda duplicando uno sí y uno no (empezando por el último);
    // el dígito verificador es el que hace que la suma total termine en 0
    static int luhnCheckDigit(String digits) {
        int sum = 0;
        boolean doubleIt = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubleIt) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleIt = !doubleIt;
        }
        return (10 - sum % 10) % 10;
    }
}
