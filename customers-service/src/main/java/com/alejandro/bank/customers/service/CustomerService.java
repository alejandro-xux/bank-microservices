package com.alejandro.bank.customers.service;

import com.alejandro.bank.customers.domain.Customer;
import com.alejandro.bank.customers.domain.CustomerStatus;
import com.alejandro.bank.customers.domain.DocumentType;
import com.alejandro.bank.customers.exception.CustomerAlreadyExistsException;
import com.alejandro.bank.customers.exception.CustomerNotFoundException;
import com.alejandro.bank.customers.repository.CustomerRepository;
import com.azure.spring.data.cosmos.exception.CosmosAccessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private static final int HTTP_CONFLICT = 409;

    private final CustomerRepository customerRepository;

    public Mono<Customer> register(Customer customer) {
        Instant now = Instant.now();
        customer.setId(UUID.randomUUID().toString());
        customer.setDocumentKey(customer.getDocumentType() + "#" + customer.getDocumentNumber());
        customer.setSearchName(normalize(
                customer.getFirstName(), customer.getPaternalLastName(), customer.getMaternalLastName()));
        customer.setStatus(CustomerStatus.ACTIVE);
        customer.setCreatedAt(now);
        customer.setUpdatedAt(now);

        return customerRepository.save(customer)
                .onErrorMap(CustomerService::isConflict, ex -> new CustomerAlreadyExistsException());
    }

    public Mono<Customer> findById(String id) {
        return customerRepository.findById(id)
                .switchIfEmpty(Mono.error(new CustomerNotFoundException(id)));
    }

    public Mono<Customer> findByDni(String dni) {
        return customerRepository.findByDocumentKey(DocumentType.DNI + "#" + dni)
                .next()
                .switchIfEmpty(Mono.error(new CustomerNotFoundException()));
    }

    public Flux<Customer> searchByName(String name, int page, int size) {
        return customerRepository.searchByName(normalize(name), page * size, size);
    }

    // "Ana María", "Quispe", "Rojas" -> "ana maria quispe rojas"
    static String normalize(String... parts) {
        String joined = Arrays.stream(parts)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" "));
        String withoutAccents = Normalizer.normalize(joined, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return withoutAccents.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private static boolean isConflict(Throwable ex) {
        return ex instanceof CosmosAccessException cosmosEx
                && cosmosEx.getCosmosException() != null
                && cosmosEx.getCosmosException().getStatusCode() == HTTP_CONFLICT;
    }
}