package com.alejandro.bank.customers.service;

import com.alejandro.bank.customers.cache.CustomerCache;
import com.alejandro.bank.customers.domain.Customer;
import com.alejandro.bank.customers.domain.CustomerStatus;
import com.alejandro.bank.customers.domain.DocumentType;
import com.alejandro.bank.customers.domain.Email;
import com.alejandro.bank.customers.domain.Phone;
import com.alejandro.bank.customers.exception.CustomerAlreadyExistsException;
import com.alejandro.bank.customers.exception.CustomerModifiedException;
import com.alejandro.bank.customers.exception.CustomerNotFoundException;
import com.alejandro.bank.customers.exception.CustomerPreconditionFailedException;
import com.alejandro.bank.customers.repository.CustomerRepository;
import com.azure.spring.data.cosmos.exception.CosmosAccessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private static final int HTTP_CONFLICT = 409;
    private static final int HTTP_PRECONDITION_FAILED = 412;

    private final CustomerRepository customerRepository;
    private final CustomerCache customerCache;

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
                .onErrorMap(ex -> hasCosmosStatus(ex, HTTP_CONFLICT), ex -> new CustomerAlreadyExistsException());
    }

    // Cache Aside: primero Redis; si no está (o Redis falla), Cosmos y se guarda en Redis
    public Mono<Customer> findById(String id) {
        return customerCache.get(id)
                .switchIfEmpty(Mono.defer(() -> findInDatabase(id)
                        .flatMap(customer -> customerCache.put(customer).thenReturn(customer))));
    }

    public Mono<Customer> findByDni(String dni) {
        return customerRepository.findByDocumentKey(DocumentType.DNI + "#" + dni)
                .next()
                .switchIfEmpty(Mono.error(new CustomerNotFoundException()));
    }

    public Flux<Customer> searchByName(String name, int page, int size) {
        return customerRepository.searchByName(normalize(name), page * size, size);
    }

    // ifMatch (opcional): versión que el cliente leyó; si ya cambió → 412.
    // Si otro cambio se cuela entre la lectura y el guardado, Cosmos responde 412 (por @Version) → 409.
    public Mono<Customer> updateContact(String id, String email, String phone, String ifMatch) {
        return findInDatabase(id)
                .flatMap(customer -> {
                    if (ifMatch != null && !ifMatch.equals("*") && !ifMatch.equals(customer.getEtag())) {
                        return Mono.error(new CustomerPreconditionFailedException());
                    }
                    if (email != null) {
                        customer.setEmails(replacePrimaryEmail(customer.getEmails(), email));
                    }
                    if (phone != null) {
                        customer.setPhones(replacePrimaryPhone(customer.getPhones(), phone));
                    }
                    customer.setUpdatedAt(Instant.now());
                    return customerRepository.save(customer)
                            .onErrorMap(ex -> hasCosmosStatus(ex, HTTP_PRECONDITION_FAILED),
                                    ex -> new CustomerModifiedException())
                            .flatMap(saved -> customerCache.evict(id).thenReturn(saved));
                });
    }

    // Lectura directa de Cosmos, la fuente de verdad (la usa updateContact para no modificar una copia del cache)
    private Mono<Customer> findInDatabase(String id) {
        return customerRepository.findById(id)
                .switchIfEmpty(Mono.error(new CustomerNotFoundException(id)));
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

    private static boolean hasCosmosStatus(Throwable ex, int statusCode) {
        return ex instanceof CosmosAccessException cosmosEx
                && cosmosEx.getCosmosException() != null
                && cosmosEx.getCosmosException().getStatusCode() == statusCode;
    }


    // Reemplaza el email principal; los demás se mantienen. El nuevo queda sin verificar.
    private static List<Email> replacePrimaryEmail(List<Email> emails, String address) {
        return emails.stream()
                .map(e -> e.primary() ? new Email(address, e.type(), true, false) : e)
                .toList();
    }

    private static List<Phone> replacePrimaryPhone(List<Phone> phones, String number) {
        return phones.stream()
                .map(p -> p.primary() ? new Phone(number, p.type(), true, false) : p)
                .toList();
    }
}