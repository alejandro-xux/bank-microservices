package com.alejandro.bank.accounts.exception;

// 422: la solicitud está bien formada, pero el cliente indicado no puede tener una cuenta nueva
public class CustomerNotEligibleException extends RuntimeException {

    private CustomerNotEligibleException(String message) {
        super(message);
    }

    public static CustomerNotEligibleException notFound(String customerId) {
        return new CustomerNotEligibleException("El cliente " + customerId + " no existe");
    }

    public static CustomerNotEligibleException blocked(String customerId) {
        return new CustomerNotEligibleException("El cliente " + customerId + " está bloqueado");
    }
}
