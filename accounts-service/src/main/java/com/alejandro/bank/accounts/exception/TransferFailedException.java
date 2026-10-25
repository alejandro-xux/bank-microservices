package com.alejandro.bank.accounts.exception;

import com.alejandro.bank.accounts.domain.Transfer;
import lombok.Getter;

// El core rechazó la transferencia y el dinero ya volvió al origen (BANK-78): 422 con su id, estado y motivo.
// La Idempotency-Key no se libera: el reintento con la misma clave devuelve este mismo resultado
@Getter
public class TransferFailedException extends RuntimeException {

    private final transient Transfer transfer;

    public TransferFailedException(Transfer transfer) {
        super("El core bancario rechazó la transferencia (código " + transfer.getFailureReason() + "); el dinero volvió a la cuenta origen");
        this.transfer = transfer;
    }
}
