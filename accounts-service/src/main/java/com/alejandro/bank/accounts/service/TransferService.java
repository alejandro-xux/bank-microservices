package com.alejandro.bank.accounts.service;

import com.alejandro.bank.accounts.domain.Transfer;
import com.alejandro.bank.accounts.exception.IdempotencyKeyConflictException;
import com.alejandro.bank.accounts.repository.TransferRepository;
import com.alejandro.bank.accounts.security.AccountAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

// Orquesta una transferencia FUERA de la transacción: autoriza (puede llamar a customers-service),
// resuelve la idempotencia y delega el movimiento del dinero en TransferExecutor
@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountAccessPolicy accessPolicy;
    private final TransferRepository transferRepository;
    private final TransferExecutor executor;

    // replayed = true si la clave ya tenía esta misma transferencia (no se movió dinero otra vez)
    public record Result(Transfer transfer, boolean replayed) {
    }

    public Result transfer(UUID idempotencyKey, UUID fromAccountId, String toAccountNumber, BigDecimal amount,
                           Authentication authentication) {
        // 1. Autorización antes que la idempotencia: con la clave de otro usuario solo se recibe 403
        accessPolicy.checkCanDebit(fromAccountId, authentication);
        String hash = requestHash(fromAccountId, toAccountNumber, amount);

        // 2. ¿Ya se hizo? (reintento normal: la respuesta anterior se perdió)
        var existing = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash, idempotencyKey);
        }
        try {
            return new Result(executor.execute(idempotencyKey, hash, requestedBy(authentication),
                    fromAccountId, toAccountNumber, amount), false);
        } catch (DataIntegrityViolationException ex) {
            // 3. Doble clic: otra petición con la misma clave ganó el INSERT. Esa transacción quedó
            // abortada; se relee en una nueva. Si no aparece, el choque fue con otra restricción
            Transfer winner = transferRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> ex);
            return replay(winner, hash, idempotencyKey);
        }
    }

    private static Result replay(Transfer transfer, String hash, UUID idempotencyKey) {
        if (!transfer.isSameRequest(hash)) {
            throw new IdempotencyKeyConflictException(idempotencyKey);
        }
        return new Result(transfer, true);
    }

    // Huella de los datos: misma clave + misma huella = reintento; misma clave + otra huella = 409.
    // El monto con 2 decimales, así "100" y "100.00" son la misma petición
    static String requestHash(UUID fromAccountId, String toAccountNumber, BigDecimal amount) {
        String data = fromAccountId + "|" + toAccountNumber + "|" + amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }

    // Quién la pidió: el oid del token (el del asesor si la ordenó él)
    private static String requestedBy(Authentication authentication) {
        if (authentication.getPrincipal() instanceof Jwt jwt && jwt.getClaimAsString("oid") != null) {
            return jwt.getClaimAsString("oid");
        }
        return authentication.getName();
    }
}
