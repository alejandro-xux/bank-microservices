package com.alejandro.bank.customers.exception;

// 409: el vínculo cuenta ↔ cliente chocaría con otro existente (no se dice cuál, para no revelar datos)
public class IdentityAlreadyLinkedException extends RuntimeException {

    private IdentityAlreadyLinkedException(String message) {
        super(message);
    }

    public static IdentityAlreadyLinkedException accountLinkedToAnotherCustomer() {
        return new IdentityAlreadyLinkedException("La cuenta ya está vinculada a otro cliente");
    }

    public static IdentityAlreadyLinkedException customerLinkedToAnotherAccount() {
        return new IdentityAlreadyLinkedException(
                "El cliente ya está vinculado a otra cuenta; desvincúlala antes de vincular una nueva");
    }
}
