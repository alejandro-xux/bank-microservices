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
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final CustomerDirectory customerDirectory;
    private final AccountAccessPolicy accessPolicy;

    // Sin @Transactional a propósito: la consulta a customers-service (hasta 2 s) va ANTES de tocar
    // la base, así no se tiene una conexión abierta esperando a otro servicio. Si esa consulta
    // falla, no se escribe nada
    public Account open(String customerId, AccountType type, Currency currency) {
        CustomerSummary customer = customerDirectory.findById(customerId);
        if (customer.isBlocked()) {
            throw CustomerNotEligibleException.blocked(customerId);
        }
        String number = AccountNumber.of(accountRepository.nextAccountNumber());
        return accountRepository.save(Account.open(customerId, type, currency, number));
    }

    // Las cuentas del cliente vinculado al token (BANK-54)
    public List<Account> myAccounts() {
        return accountRepository.findByCustomerIdOrderByCreatedAtAsc(accessPolicy.ownCustomerId());
    }

    // Una cuenta, con la regla del dueño (AccountAccessPolicy)
    public Account findById(UUID id, Authentication authentication) {
        return accessPolicy.accessibleAccount(id, authentication);
    }

    // Búsqueda del asesor: sin llamar a customers-service (un id sin cuentas → lista vacía)
    public List<Account> findByCustomer(String customerId) {
        return accountRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
    }
}
