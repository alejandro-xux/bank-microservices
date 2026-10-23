package com.alejandro.bank.customers.service;

import com.alejandro.bank.customers.domain.Customer;
import com.alejandro.bank.customers.domain.CustomerStatus;
import com.alejandro.bank.customers.domain.DocumentType;
import com.alejandro.bank.customers.exception.CustomerAlreadyExistsException;
import com.alejandro.bank.customers.exception.CustomerNotFoundException;
import com.alejandro.bank.customers.repository.CustomerRepository;
import com.azure.cosmos.CosmosException;
import com.azure.spring.data.cosmos.exception.CosmosAccessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    CustomerRepository customerRepository;

    @InjectMocks
    CustomerService customerService;

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



    private Customer newCustomer() {
        return Customer.builder()
                .documentType(DocumentType.DNI)
                .documentNumber("12345678")
                .firstName("Ana María")
                .paternalLastName("Quispe")
                .maternalLastName("Rojas")
                .build();
    }
}