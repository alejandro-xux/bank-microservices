package com.alejandro.bank.notifications.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.contracts.transfers.v1.TransferFailed;
import com.google.protobuf.Timestamp;

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
        Timestamp at = event.getCompletedAt();
        String operation = event.getTransferId();
        return switch (recipient) {
            case SENDER -> new Mail("Enviaste " + money,
                    body(name, "Enviaste " + money + " desde tu cuenta " + from + " a la cuenta " + to + ".", at, operation));
            case RECEIVER -> new Mail("Recibiste " + money,
                    body(name, "Recibiste " + money + " en tu cuenta " + to + " desde la cuenta " + from + ".", at, operation));
            case SELF -> new Mail("Moviste " + money + " entre tus cuentas",
                    body(name, "Moviste " + money + " de tu cuenta " + from + " a tu cuenta " + to + ".", at, operation));
        };
    }

    // BANK-80: solo al que envía. Que sepa que no se hizo y que el dinero ya está otra vez en su cuenta
    static Mail failed(TransferFailed event, String name) {
        String money = event.getAmount() + " " + event.getCurrency();
        return new Mail("No pudimos completar tu transferencia de " + money,
                body(name, "No pudimos completar tu transferencia de " + money + " desde tu cuenta "
                                + masked(event.getFromAccountNumber()) + ". Te devolvimos el dinero a esa cuenta.\n"
                                + "Motivo: rechazada por el sistema central del banco (código " + event.getReason() + ").",
                        event.getFailedAt(), event.getTransferId()));
    }

    private static String body(String name, String what, Timestamp at, String operation) {
        Instant when = Instant.ofEpochSecond(at.getSeconds(), at.getNanos());
        return "Hola " + name + ":\n\n"
                + what + "\n"
                + "Fecha: " + WHEN.format(when) + " (hora de Lima)\n"
                + "Operación: " + operation + "\n\n"
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
