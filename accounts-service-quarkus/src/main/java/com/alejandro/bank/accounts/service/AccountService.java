package com.alejandro.bank.accounts.service;

import com.alejandro.bank.accounts.customers.CustomerDirectory;
import com.alejandro.bank.accounts.customers.CustomerSummary;
import com.alejandro.bank.accounts.domain.Account;
import com.alejandro.bank.accounts.domain.AccountNumber;
import com.alejandro.bank.accounts.domain.AccountType;
import com.alejandro.bank.accounts.domain.Currency;
import com.alejandro.bank.accounts.exception.CustomerNotEligibleException;
import com.alejandro.bank.accounts.repository.AccountRepository;
import com.alejandro.bank.accounts.security.AccountAccessPolicy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class AccountService {

    @Inject
    AccountRepository accountRepository;

    @Inject
    CustomerDirectory customerDirectory;

    @Inject
    AccountAccessPolicy accessPolicy;

    // La consulta a customers-service (hasta 2 s) va ANTES de abrir la transacción, como en accounts-service: no se
    // tiene una conexión abierta esperando a otro servicio. Si esa consulta falla, no se escribe nada
    public Account open(String customerId, AccountType type, Currency currency) {
        CustomerSummary customer = customerDirectory.findById(customerId);
        if (customer.isBlocked()) {
            throw CustomerNotEligibleException.blocked(customerId);
        }
        return QuarkusTransaction.requiringNew().call(() -> {
            Account account = Account.open(customerId, type, currency,
                    AccountNumber.of(accountRepository.nextAccountNumber()));
            accountRepository.persist(account);
            return account;
        });
    }

    public List<Account> myAccounts() {
        return accountRepository.findByCustomerIdOrderByCreatedAtAsc(accessPolicy.ownCustomerId());
    }

    public Account findById(UUID id) {
        return accessPolicy.accessibleAccount(id);
    }

    // Búsqueda del asesor: sin llamar a customers-service (un id sin cuentas → lista vacía)
    public List<Account> findByCustomer(String customerId) {
        return accountRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
    }
}
