package com.alejandro.bank.customers.service;

import com.alejandro.bank.customers.cache.CustomerCache;
import com.alejandro.bank.customers.domain.*;
import com.alejandro.bank.customers.exception.CustomerAlreadyExistsException;
import com.alejandro.bank.customers.exception.CustomerModifiedException;
import com.alejandro.bank.customers.exception.CustomerNotFoundException;
import com.alejandro.bank.customers.exception.CustomerPreconditionFailedException;
import com.alejandro.bank.customers.repository.CustomerRepository;
import com.azure.cosmos.CosmosException;
import com.azure.spring.data.cosmos.exception.CosmosAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;


import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    CustomerRepository customerRepository;

    @Mock
    CustomerCache customerCache;

    @InjectMocks
    CustomerService customerService;

    // Por defecto el cache está vacío (miss) y guardar no falla; cada prueba puede cambiarlo
    @BeforeEach
    void emptyCache() {
        lenient().when(customerCache.get(any())).thenReturn(Mono.empty());
        lenient().when(customerCache.put(any())).thenReturn(Mono.empty());
        lenient().when(customerCache.evict(any())).thenReturn(Mono.empty());
    }

    @Test
    void updateContactEvictsCacheAfterSaving() {
        when(customerRepository.findById("abc-123")).thenReturn(Mono.just(customerWithContacts()));
        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(customerService.updateContact("abc-123", "nuevo@mail.com", null, null))
                .expectNextCount(1)
                .verifyComplete();

        InOrder inOrder = inOrder(customerRepository, customerCache);
        inOrder.verify(customerRepository).save(any(Customer.class));
        inOrder.verify(customerCache).evict("abc-123");
    }

    @Test
    void updateContactWithMatchingIfMatchSaves() {
        when(customerRepository.findById("abc-123")).thenReturn(Mono.just(customerWithContacts()));
        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(customerService.updateContact("abc-123", "nuevo@mail.com", null, "\"v1\""))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void updateContactWithStaleIfMatchFailsWith412AndDoesNotSave() {
        when(customerRepository.findById("abc-123")).thenReturn(Mono.just(customerWithContacts()));

        StepVerifier.create(customerService.updateContact("abc-123", "nuevo@mail.com", null, "\"v0\""))
                .expectError(CustomerPreconditionFailedException.class)
                .verify();

        verify(customerRepository, never()).save(any());
    }

    @Test
    void updateContactWhenCosmosRejectsVersionFailsWithModifiedAndDoesNotEvict() {
        CosmosException preconditionFailed = mock(CosmosException.class);
        when(preconditionFailed.getStatusCode()).thenReturn(412);
        CosmosAccessException cosmosError = new CosmosAccessException("precondition failed", preconditionFailed);
        when(customerRepository.findById("abc-123")).thenReturn(Mono.just(customerWithContacts()));
        when(customerRepository.save(any(Customer.class))).thenReturn(Mono.error(cosmosError));

        StepVerifier.create(customerService.updateContact("abc-123", "nuevo@mail.com", null, null))
                .expectError(CustomerModifiedException.class)
                .verify();

        verify(customerCache, never()).evict(any());
    }

    @Test
    void updateContactDoesNotEvictWhenSaveFails() {
        when(customerRepository.findById("abc-123")).thenReturn(Mono.just(customerWithContacts()));
        when(customerRepository.save(any(Customer.class)))
                .thenReturn(Mono.error(new IllegalStateException("Cosmos no disponible")));

        StepVerifier.create(customerService.updateContact("abc-123", "nuevo@mail.com", null, null))
                .expectError(IllegalStateException.class)
                .verify();

        verify(customerCache, never()).evict(any());
    }

    @Test
    void findByIdWithCacheHitDoesNotQueryCosmos() {
        Customer cached = newCustomer();
        cached.setId("abc-123");
        when(customerCache.get("abc-123")).thenReturn(Mono.just(cached));

        StepVerifier.create(customerService.findById("abc-123"))
                .assertNext(found -> assertThat(found.getId()).isEqualTo("abc-123"))
                .verifyComplete();

        verify(customerRepository, never()).findById(anyString());
    }

    @Test
    void findByIdWithCacheMissReadsCosmosAndStoresInCache() {
        Customer existing = newCustomer();
        existing.setId("abc-123");
        when(customerRepository.findById("abc-123")).thenReturn(Mono.just(existing));

        StepVerifier.create(customerService.findById("abc-123"))
                .assertNext(found -> assertThat(found.getId()).isEqualTo("abc-123"))
                .verifyComplete();

        verify(customerCache).put(existing);
    }

    @Test
    void findByIdWithUnknownIdDoesNotStoreInCache() {
        when(customerRepository.findById("no-existe")).thenReturn(Mono.empty());

        StepVerifier.create(customerService.findById("no-existe"))
                .expectError(CustomerNotFoundException.class)
                .verify();

        verify(customerCache, never()).put(any());
    }

    private Customer newCustomer() {
        return Customer.builder()
                .documentType(DocumentType.DNI)
                .documentNumber("12345678")
                .firstName("Ana María")
                .paternalLastName("Quispe")
                .maternalLastName("Rojas")
                .build();
    }

    @Test
    void registerCompletesCustomerAndSavesIt() {
        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(customerService.register(newCustomer()))
                .assertNext(saved -> {
                    assertThat(saved.getId()).isNotBlank();
                    assertThat(saved.getDocumentKey()).isEqualTo("DNI#12345678");
                    assertThat(saved.getSearchName()).isEqualTo("ana maria quispe rojas");
                    assertThat(saved.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
                    assertThat(saved.getCreatedAt()).isNotNull();
                    assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
                })
                .verifyComplete();
    }

    @Test
    void registerWithDuplicatedDocumentFailsWithConflict() {
        CosmosException conflict = mock(CosmosException.class);
        when(conflict.getStatusCode()).thenReturn(409);
        CosmosAccessException cosmosError = new CosmosAccessException("conflict", conflict);
        when(customerRepository.save(any(Customer.class))).thenReturn(Mono.error(cosmosError));

        StepVerifier.create(customerService.register(newCustomer()))
                .expectError(CustomerAlreadyExistsException.class)
                .verify();
    }

    @Test
    void registerKeepsOtherErrorsUnchanged() {
        when(customerRepository.save(any(Customer.class)))
                .thenReturn(Mono.error(new IllegalStateException("Cosmos no disponible")));

        StepVerifier.create(customerService.register(newCustomer()))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void findByIdReturnsCustomer() {
        Customer existing = newCustomer();
        existing.setId("abc-123");
        when(customerRepository.findById("abc-123")).thenReturn(Mono.just(existing));

        StepVerifier.create(customerService.findById("abc-123"))
                .assertNext(found -> assertThat(found.getId()).isEqualTo("abc-123"))
                .verifyComplete();
    }

    @Test
    void findByIdWithUnknownIdFailsWithNotFound() {
        when(customerRepository.findById("no-existe")).thenReturn(Mono.empty());

        StepVerifier.create(customerService.findById("no-existe"))
                .expectError(CustomerNotFoundException.class)
                .verify();
    }

    @Test
    void normalizeRemovesAccentsCaseAndExtraSpaces() {
        assertThat(CustomerService.normalize("  Ana  María ", "NÚÑEZ", null))
                .isEqualTo("ana maria nunez");
    }

    @Test
    void findByDniReturnsCustomer() {
        when(customerRepository.findByDocumentKey("DNI#12345678")).thenReturn(Flux.just(newCustomer()));

        StepVerifier.create(customerService.findByDni("12345678"))
                .assertNext(found -> assertThat(found.getDocumentNumber()).isEqualTo("12345678"))
                .verifyComplete();
    }

    @Test
    void findByDniWithUnknownDniFailsWithNotFound() {
        when(customerRepository.findByDocumentKey("DNI#87654321")).thenReturn(Flux.empty());

        StepVerifier.create(customerService.findByDni("87654321"))
                .expectError(CustomerNotFoundException.class)
                .verify();
    }

    @Test
    void searchByNameNormalizesTextAndCalculatesOffset() {
        when(customerRepository.searchByName("quispe", 20, 20)).thenReturn(Flux.just(newCustomer()));

        StepVerifier.create(customerService.searchByName("  QUÍSPE ", 1, 20))
                .expectNextCount(1)
                .verifyComplete();
    }
    @Test
    void updateContactReplacesPrimaryEmailAndKeepsTheOthers() {
        when(customerRepository.findById("abc-123")).thenReturn(Mono.just(customerWithContacts()));
        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(customerService.updateContact("abc-123", "nuevo@mail.com", null, null))
                .assertNext(updated -> {
                    assertThat(updated.getEmails()).containsExactly(
                            new Email("nuevo@mail.com", Email.Type.PERSONAL, true, false),
                            new Email("ana.q@empresa.pe", Email.Type.WORK, false, true));
                    assertThat(updated.getPhones().getFirst().number()).isEqualTo("+51987654321");
                    assertThat(updated.getUpdatedAt()).isAfter(updated.getCreatedAt());
                })
                .verifyComplete();
    }

    @Test
    void updateContactOnlyPhoneKeepsEmail() {
        when(customerRepository.findById("abc-123")).thenReturn(Mono.just(customerWithContacts()));
        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(customerService.updateContact("abc-123", null, "+51911111111", null))
                .assertNext(updated -> {
                    assertThat(updated.getPhones().getFirst())
                            .isEqualTo(new Phone("+51911111111", Phone.Type.MOBILE, true, false));
                    assertThat(updated.getEmails().getFirst().address()).isEqualTo("ana@mail.com");
                })
                .verifyComplete();
    }

    @Test
    void updateContactWithUnknownIdFailsWithNotFoundAndDoesNotSave() {
        when(customerRepository.findById("no-existe")).thenReturn(Mono.empty());

        StepVerifier.create(customerService.updateContact("no-existe", "nuevo@mail.com", null, null))
                .expectError(CustomerNotFoundException.class)
                .verify();

        verify(customerRepository, never()).save(any());
    }

    @Test
    void findByIdentityIdReturnsLinkedCustomer() {
        Customer ana = Customer.builder().id("abc-123").identityId("oid-ana").build();
        when(customerRepository.findByIdentityId("oid-ana")).thenReturn(Flux.just(ana));

        StepVerifier.create(customerService.findByIdentityId("oid-ana"))
                .expectNext(ana)
                .verifyComplete();
    }

    @Test
    void findByIdentityIdWithoutLinkedCustomerIsEmpty() {
        when(customerRepository.findByIdentityId("oid-sin-cliente")).thenReturn(Flux.empty());

        StepVerifier.create(customerService.findByIdentityId("oid-sin-cliente"))
                .verifyComplete();
    }

    // Dato inconsistente (dos clientes con el mismo vínculo): se usa el primero
    @Test
    void findByIdentityIdWithDuplicatedLinkUsesFirst() {
        Customer first = Customer.builder().id("abc-123").identityId("oid-ana").build();
        Customer second = Customer.builder().id("def-456").identityId("oid-ana").build();
        when(customerRepository.findByIdentityId("oid-ana")).thenReturn(Flux.just(first, second));

        StepVerifier.create(customerService.findByIdentityId("oid-ana"))
                .expectNext(first)
                .verifyComplete();
    }

    private Customer customerWithContacts() {
        Customer customer = newCustomer();
        customer.setId("abc-123");
        customer.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        customer.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        customer.setEmails(List.of(
                new Email("ana@mail.com", Email.Type.PERSONAL, true, true),
                new Email("ana.q@empresa.pe", Email.Type.WORK, false, true)));
        customer.setPhones(List.of(new Phone("+51987654321", Phone.Type.MOBILE, true, true)));
        customer.setEtag("\"v1\"");
        return customer;
    }
}