package com.alejandro.bank.customers.domain;

public record Phone(String number, Type type, boolean primary, boolean verified) {

    public enum Type {
        MOBILE, HOME, WORK
    }
}