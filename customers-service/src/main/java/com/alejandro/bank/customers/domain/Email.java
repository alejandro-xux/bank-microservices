package com.alejandro.bank.customers.domain;

public record Email(String address, Type type, boolean primary, boolean verified) {

    public enum Type {
        PERSONAL, WORK
    }
}