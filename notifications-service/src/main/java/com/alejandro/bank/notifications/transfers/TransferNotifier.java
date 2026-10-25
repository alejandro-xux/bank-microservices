package com.alejandro.bank.notifications.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// Qué se hace con una transferencia completada. Por ahora la deja en el log; el correo al que envía y al que
// recibe llega en BANK-72. En el log no van datos personales ni números de cuenta completos
@Service
@Slf4j
public class TransferNotifier {

    public void notify(TransferCompleted event) {
        log.info("Transferencia {}: {} {} de la cuenta {} a la {} (evento {})", event.getTransferId(),
                event.getAmount(), event.getCurrency(), masked(event.getFromAccountNumber()),
                masked(event.getToAccountNumber()), event.getEventId());
    }

    // 001-0000000003-2 → 001-******0003-2, como en el historial de movimientos
    static String masked(String number) {
        if (number.length() != 16) {
            return "***";
        }
        return number.substring(0, 4) + "******" + number.substring(10);
    }
}
