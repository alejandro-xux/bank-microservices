package com.alejandro.bank.accounts.probe;

// TEMPORAL (prueba al revés de BANK-106): mitad probada, mitad no. Se borra antes del merge
public final class CoveragePrueba {

    private CoveragePrueba() {
    }

    public static String classify(int n) {
        if (n < 0) {
            return "negativo";
        }
        if (n == 0) {
            return "cero";
        }
        return "positivo";
    }

    public static int capped(int a, int b) {
        int sum = a + b;
        if (sum > 100) {
            sum = 100;
        }
        return sum;
    }

    public static String label(int n) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < n; i++) {
            text.append('x');
        }
        if (text.length() > 3) {
            text.setLength(3);
        }
        return text.toString();
    }

    public static int sign(int n) {
        if (n > 0) {
            return 1;
        } else if (n < 0) {
            return -1;
        }
        return 0;
    }
}
