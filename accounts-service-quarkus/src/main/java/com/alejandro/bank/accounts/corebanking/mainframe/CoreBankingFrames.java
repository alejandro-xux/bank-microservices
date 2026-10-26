package com.alejandro.bank.accounts.corebanking.mainframe;

import com.alejandro.bank.accounts.corebanking.CoreBankingEntry;
import com.alejandro.bank.accounts.corebanking.PostingResult;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

// Las tramas de largo fijo del core (BANK-77, spike BANK-74 S4/S5): el equivalente al copybook COBOL. Es el ÚNICO
// lugar que conoce el formato; cada trama se arma campo por campo con su largo, nunca escribiendo la cadena a mano
// (en BANK-74 y BANK-76 una trama escrita a mano salió dos veces con un dígito de menos).
//
//   ASIENTO  (75) TRX | fecha y hora 14 | id-op 12 | cuenta origen 14 | cuenta destino 14 | moneda 3 | monto 15
//   CONSULTA (47) QRY | fecha y hora 14 | id-op 12 | moneda 3 | monto 15
//   VUELTA   (52) código 3 | TRX o QRY | fecha y hora 14 | id-op 12 | moneda 3 | monto 15 | estado 2
public final class CoreBankingFrames {

    static final int POSTING_LENGTH = 75;
    static final int QUERY_LENGTH = 47;
    static final int REPLY_LENGTH = 52;

    private static final int OPERATION = 12;
    private static final int ACCOUNT = 14;
    private static final int AMOUNT = 15;
    // La hora del core es la de Lima (un mainframe no guarda zonas horarias)
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("America/Lima"));

    private CoreBankingFrames() {
    }

    static String posting(CoreBankingEntry entry) {
        return check(POSTING_LENGTH, "TRX" + STAMP.format(entry.at()) + operation(entry.operationNumber())
                + account(entry.fromAccountNumber()) + account(entry.toAccountNumber())
                + entry.currency().name() + cents(entry.amount()));
    }

    static String query(CoreBankingEntry entry) {
        return check(QUERY_LENGTH, "QRY" + STAMP.format(entry.at()) + operation(entry.operationNumber())
                + entry.currency().name() + cents(entry.amount()));
    }

    // Respuesta del core → resultado. Una trama que no es la esperada (largo, operación o id-op distintos) es
    // UNKNOWN: no se entiende, así que no se puede suponer que lo rechazó
    static PostingResult reply(String frame, String expectedOperation, long expectedOperationNumber) {
        if (frame == null || frame.length() != REPLY_LENGTH) {
            return PostingResult.unknown("trama de " + (frame == null ? 0 : frame.length()) + " caracteres, se esperaban " + REPLY_LENGTH);
        }
        String code = frame.substring(0, 3);
        String operation = frame.substring(3, 6);
        String operationNumber = frame.substring(20, 20 + OPERATION);
        String state = frame.substring(50, 52);
        if (!operation.equals(expectedOperation) || !operationNumber.equals(operation(expectedOperationNumber))) {
            return PostingResult.unknown("respuesta de otra operación (" + operation + " " + operationNumber + ")");
        }
        return switch (state) {
            case "OK" -> new PostingResult(PostingResult.Outcome.POSTED, code);
            case "RC" -> new PostingResult(PostingResult.Outcome.REJECTED, code);
            case "NF" -> new PostingResult(PostingResult.Outcome.NOT_FOUND, code);
            default -> PostingResult.unknown("estado desconocido '" + state + "'");
        };
    }

    // 12345 → 000000012345
    static String operation(long number) {
        return digits(number, OPERATION, "número de operación");
    }

    // 001-0000000001-6 → 00100000000016 (el core no usa guiones)
    static String account(String number) {
        String digits = number.replace("-", "");
        if (digits.length() != ACCOUNT || !digits.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Número de cuenta inválido para el core: " + number);
        }
        return digits;
    }

    // 100.00 → 000000000010000: céntimos con dos decimales implícitos (PIC 9(13)V99)
    static String cents(BigDecimal amount) {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Monto negativo para el core: " + amount);
        }
        return digits(amount.movePointRight(2).longValueExact(), AMOUNT, "monto");
    }

    private static String digits(long value, int width, String what) {
        String text = Long.toString(value);
        if (text.length() > width) {
            throw new IllegalArgumentException("El " + what + " " + value + " no entra en " + width + " dígitos");
        }
        return "0".repeat(width - text.length()) + text;
    }

    private static String check(int expected, String frame) {
        if (frame.length() != expected) {
            throw new IllegalStateException("Trama de " + frame.length() + " caracteres, se esperaban " + expected);
        }
        return frame;
    }
}
