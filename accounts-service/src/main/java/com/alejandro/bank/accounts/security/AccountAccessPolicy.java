package com.alejandro.bank.accounts.security;

import com.alejandro.bank.accounts.config.SecurityConfig;
import com.alejandro.bank.accounts.customers.CustomerDirectory;
import com.alejandro.bank.accounts.domain.Account;
import com.alejandro.bank.accounts.exception.AccountNotFoundException;
import com.alejandro.bank.accounts.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Regla del dueño (como CustomerAccessPolicy en customers-service, BANK-37): el asesor ve cualquier
// cuenta; un CUSTOMER solo las de su cliente. Quién es su cliente lo dice customers-service (/me):
// el vínculo cuenta de acceso ↔ cliente no se copia aquí, porque el asesor puede cambiarlo (BANK-48)
@Component
@RequiredArgsConstructor
public class AccountAccessPolicy {

    private static final String ROLE_PREFIX = "ROLE_";

    private final AccountRepository accountRepository;
    private final CustomerDirectory customerDirectory;

    // Id del cliente vinculado al token; sin vínculo → 403, customers caído → 503
    public String ownCustomerId() {
        return customerDirectory.findMe().id();
    }

    // ADMIN: la cuenta o 404. CUSTOMER: primero quién es y luego la cuenta, así una ajena y una
    // inexistente siguen el mismo camino y terminan en el mismo 403 (no revela qué ids existen)
    public Account accessibleAccount(UUID id, Authentication authentication) {
        if (hasRole(authentication, SecurityConfig.ADMIN)) {
            return accountRepository.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        }
        String customerId = ownCustomerId();
        return accountRepository.findById(id)
                .filter(account -> account.getCustomerId().equals(customerId))
                .orElseThrow(() -> new AccessDeniedException("Sin acceso a la cuenta"));
    }

    // Transferir desde una cuenta (BANK-56): el asesor desde cualquiera; un CUSTOMER solo desde las
    // suyas, y una ajena o inexistente da el mismo 403. Es una proyección: no deja la cuenta cargada
    // antes de que la transferencia la bloquee
    public void checkCanDebit(UUID accountId, Authentication authentication) {
        if (hasRole(authentication, SecurityConfig.ADMIN)) {
            return;
        }
        String customerId = ownCustomerId();
        boolean own = accountRepository.findCustomerIdById(accountId)
                .filter(customerId::equals)
                .isPresent();
        if (!own) {
            throw new AccessDeniedException("Sin acceso a la cuenta");
        }
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> (ROLE_PREFIX + role).equals(authority.getAuthority()));
    }
}
