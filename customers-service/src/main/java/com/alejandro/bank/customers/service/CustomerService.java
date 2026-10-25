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
import com.alejandro.bank.customers.exception.IdentityAlreadyLinkedException;
import com.alejandro.bank.customers.repository.CustomerRepository;
import com.azure.spring.data.cosmos.exception.CosmosAccessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
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

    // Cliente vinculado a la cuenta (identityId = oid del token); vacío si no hay ninguno.
    // Dos clientes con el mismo vínculo sería un error de datos: se usa el primero y se avisa en el log.
    public Mono<Customer> findByIdentityId(String identityId) {
        return customerRepository.findByIdentityId(identityId)
                .take(2)
                .collectList()
                .flatMap(customers -> {
                    if (customers.size() > 1) {
                        log.warn("Más de un cliente vinculado a la misma identidad; se usa {}", customers.getFirst().getId());
                    }
                    return Mono.justOrEmpty(customers.stream().findFirst());
                });
    }

    public Flux<Customer> searchByName(String name, int page, int size) {
        return customerRepository.searchByName(normalize(name), page * size, size);
    }

    // ifMatch (opcional): versión que el cliente leyó; si ya cambió → 412.
    // Si otro cambio se cuela entre la lectura y el guardado, Cosmos responde 412 (por @Version) → 409.
    public Mono<Customer> updateContact(String id, String email, String phone, String ifMatch) {
        return findInDatabase(id)
                .flatMap(customer -> checkVersion(customer, ifMatch))
                .flatMap(customer -> {
                    if (email != null) {
                        customer.setEmails(replacePrimaryEmail(customer.getEmails(), email));
                    }
                    if (phone != null) {
                        customer.setPhones(replacePrimaryPhone(customer.getPhones(), phone));
                    }
                    return saveAndEvict(customer);
                });
    }

    // Vincula la cuenta de acceso (oid) con el cliente. Mismo vínculo → sin cambios (idempotente);
    // cliente con otra cuenta, o cuenta en otro cliente → 409. Solo cambia identityId y updatedAt.
    // Limitación: "cuenta en otro cliente" se revisa leyendo y luego escribiendo (Cosmos no tiene
    // unique key entre particiones); dos vínculos simultáneos del mismo oid podrían pasar.
    public Mono<Customer> linkIdentity(String id, String identityId, String ifMatch) {
        return findInDatabase(id)
                .flatMap(customer -> checkVersion(customer, ifMatch))
                .flatMap(customer -> {
                    if (identityId.equals(customer.getIdentityId())) {
                        return Mono.just(customer);
                    }
                    if (customer.getIdentityId() != null) {
                        return Mono.error(IdentityAlreadyLinkedException.customerLinkedToAnotherAccount());
                    }
                    return customerRepository.findByIdentityId(identityId).hasElements()
                            .flatMap(linkedElsewhere -> {
                                if (linkedElsewhere) {
                                    return Mono.error(IdentityAlreadyLinkedException.accountLinkedToAnotherCustomer());
                                }
                                customer.setIdentityId(identityId);
                                return saveAndEvict(customer);
                            });
                })
                .doOnNext(customer -> log.info("Cuenta de acceso vinculada al cliente {}", id));
    }

    // Quita el vínculo con la cuenta de acceso; si no tenía, no hace nada (idempotente)
    public Mono<Void> unlinkIdentity(String id, String ifMatch) {
        return findInDatabase(id)
                .flatMap(customer -> checkVersion(customer, ifMatch))
                .flatMap(customer -> {
                    if (customer.getIdentityId() == null) {
                        return Mono.just(customer);
                    }
                    customer.setIdentityId(null);
                    return saveAndEvict(customer)
                            .doOnNext(saved -> log.info("Cuenta de acceso desvinculada del cliente {}", id));
                })
                .then();
    }

    // ifMatch (opcional): versión que el cliente leyó; si ya cambió → 412
    private static Mono<Customer> checkVersion(Customer customer, String ifMatch) {
        if (ifMatch != null && !ifMatch.equals("*") && !ifMatch.equals(customer.getEtag())) {
            return Mono.error(new CustomerPreconditionFailedException());
        }
        return Mono.just(customer);
    }

    // Guarda con el _etag leído (si otro cambio se coló, Cosmos responde 412 → 409) e invalida el cache
    private Mono<Customer> saveAndEvict(Customer customer) {
        customer.setUpdatedAt(Instant.now());
        return customerRepository.save(customer)
                .onErrorMap(ex -> hasCosmosStatus(ex, HTTP_PRECONDITION_FAILED), ex -> new CustomerModifiedException())
                .flatMap(saved -> customerCache.evict(customer.getId()).thenReturn(saved));
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