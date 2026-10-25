package com.alejandro.bank.accounts.service;

import com.alejandro.bank.accounts.customers.CustomerSummary;
import com.alejandro.bank.accounts.customers.CustomersClient;
import com.alejandro.bank.accounts.domain.Account;
import com.alejandro.bank.accounts.domain.AccountNumber;
import com.alejandro.bank.accounts.domain.AccountType;
import com.alejandro.bank.accounts.domain.Currency;
import com.alejandro.bank.accounts.exception.CustomerNotEligibleException;
import com.alejandro.bank.accounts.exception.CustomersUnavailableException;
import com.alejandro.bank.accounts.repository.AccountRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final CustomersClient customersClient;

    // Sin @Transactional a propósito: la consulta a customers-service (hasta 2 s) va ANTES de tocar
    // la base, así no se tiene una conexión abierta esperando a otro servicio. Si esa consulta
    // falla, no se escribe nada
    public Account open(String customerId, AccountType type, Currency currency) {
        CustomerSummary customer = findCustomer(customerId);
        if (customer.isBlocked()) {
            throw CustomerNotEligibleException.blocked(customerId);
        }
        String number = AccountNumber.of(accountRepository.nextAccountNumber());
        return accountRepository.save(Account.open(customerId, type, currency, number));
    }

    // 404 de customers-service → 422; cualquier otro error, timeout o caída → 503
    private CustomerSummary findCustomer(String customerId) {
        try {
            return customersClient.findById(customerId);
        } catch (FeignException.NotFound ex) {
            throw CustomerNotEligibleException.notFound(customerId);
        } catch (FeignException ex) {
            log.warn("customers-service no disponible al abrir una cuenta para {}: {}", customerId, ex.getMessage());
            throw new CustomersUnavailableException(ex);
        }
    }
}
