package com.alejandro.bank.notifications.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

// Asunto y texto de cada aviso de transferencia (BANK-72). Los números de cuenta siempre enmascarados: un correo
// se reenvía, se imprime y queda en bandejas que el banco no controla
final class TransferMails {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(LIMA);

    record Mail(String subject, String text) {
    }

    private TransferMails() {
    }

    static Mail of(TransferCompleted event, Recipient recipient, String name) {
        String money = event.getAmount() + " " + event.getCurrency();
        String from = masked(event.getFromAccountNumber());
        String to = masked(event.getToAccountNumber());
        return switch (recipient) {
            case SENDER -> new Mail("Enviaste " + money,
                    body(name, "Enviaste " + money + " desde tu cuenta " + from + " a la cuenta " + to + ".", event));
            case RECEIVER -> new Mail("Recibiste " + money,
                    body(name, "Recibiste " + money + " en tu cuenta " + to + " desde la cuenta " + from + ".", event));
            case SELF -> new Mail("Moviste " + money + " entre tus cuentas",
                    body(name, "Moviste " + money + " de tu cuenta " + from + " a tu cuenta " + to + ".", event));
        };
    }

    private static String body(String name, String what, TransferCompleted event) {
        Instant when = Instant.ofEpochSecond(event.getCompletedAt().getSeconds(), event.getCompletedAt().getNanos());
        return "Hola " + name + ":\n\n"
                + what + "\n"
                + "Fecha: " + WHEN.format(when) + " (hora de Lima)\n"
                + "Operación: " + event.getTransferId() + "\n\n"
                + "Si no reconoces esta operación, comunícate con el banco.\n";
    }

    // 001-0000000003-2 → 001-******0003-2, como en el historial de movimientos
    static String masked(String number) {
        if (number.length() != 16) {
            return "***";
        }
        return number.substring(0, 4) + "******" + number.substring(10);
    }
}
