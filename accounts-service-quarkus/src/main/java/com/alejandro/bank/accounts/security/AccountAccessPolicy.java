package com.alejandro.bank.accounts.security;

import com.alejandro.bank.accounts.customers.CustomerDirectory;
import com.alejandro.bank.accounts.domain.Account;
import com.alejandro.bank.accounts.exception.AccountNotFoundException;
import com.alejandro.bank.accounts.repository.AccountRepository;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

// Regla del dueño, igual que en accounts-service: el asesor ve cualquier cuenta; un CUSTOMER solo las de su cliente,
// y quién es su cliente lo dice customers-service (/me). Una cuenta ajena y una inexistente dan el mismo 403
@ApplicationScoped
public class AccountAccessPolicy {

    public static final String ADMIN = "ADMIN";
    public static final String CUSTOMER = "CUSTOMER";

    @Inject
    SecurityIdentity identity;

    @Inject
    AccountRepository accountRepository;

    @Inject
    CustomerDirectory customerDirectory;

    // Id del cliente vinculado al token; sin vínculo → 403, customers caído → 503
    public String ownCustomerId() {
        return customerDirectory.findMe().id();
    }

    // ADMIN: la cuenta o 404. CUSTOMER: primero quién es y luego la cuenta (no revela qué ids existen)
    public Account accessibleAccount(UUID id) {
        if (isAdmin()) {
            return accountRepository.findByIdOptional(id).orElseThrow(() -> new AccountNotFoundException(id));
        }
        String customerId = ownCustomerId();
        return accountRepository.findByIdOptional(id)
                .filter(account -> account.getCustomerId().equals(customerId))
                .orElseThrow(() -> new ForbiddenException("Sin acceso a la cuenta"));
    }

    // Transferir desde una cuenta (BANK-101): el asesor desde cualquiera; un CUSTOMER solo desde las suyas
    public void checkCanDebit(UUID accountId) {
        if (isAdmin()) {
            return;
        }
        String customerId = ownCustomerId();
        boolean own = accountRepository.findCustomerIdById(accountId).filter(customerId::equals).isPresent();
        if (!own) {
            throw new ForbiddenException("Sin acceso a la cuenta");
        }
    }

    public boolean isAdmin() {
        return identity.hasRole(ADMIN);
    }
}
