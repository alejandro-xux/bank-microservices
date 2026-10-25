package com.alejandro.bank.accounts.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Collectors;

// Huella de los datos de una petición con Idempotency-Key (transferencias BANK-56, depósitos BANK-64):
// misma clave + misma huella = reintento; misma clave + otra huella = 409. Los montos van con 2 decimales,
// así "100" y "100.00" son la misma petición
final class RequestHash {

    private RequestHash() {
    }

    static String of(Object... parts) {
        String data = Arrays.stream(parts)
                .map(part -> part instanceof BigDecimal amount
                        ? amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                        : String.valueOf(part))
                .collect(Collectors.joining("|"));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }
}
