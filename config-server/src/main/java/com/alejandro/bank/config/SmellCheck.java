package com.alejandro.bank.config;

// TEMPORAL BANK-97: olores a propósito para comprobar que Sonar detecta code smells. Se borra antes del merge
public class SmellCheck {

    private int unused;

    public void print(String text) {
        System.out.println("texto: " + text);
        String copy = text;
    }
}
