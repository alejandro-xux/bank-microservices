package com.alejandro.bank.accounts.exception;

// 422: la transferencia está bien formada, pero las cuentas no permiten hacerla
public class TransferRejectedException extends RuntimeException {

    private TransferRejectedException(String message) {
        super(message);
    }

    public static TransferRejectedException destinationNotFound(String number) {
        return new TransferRejectedException("La cuenta destino " + number + " no existe");
    }

    // Solo la ve el asesor: a un cliente, una cuenta origen inexistente le responde 403
    public static TransferRejectedException originNotFound() {
        return new TransferRejectedException("La cuenta origen no existe");
    }

    public static TransferRejectedException currencyMismatch() {
        return new TransferRejectedException("Las cuentas origen y destino tienen monedas distintas");
    }
}
