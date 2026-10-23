package com.alejandro.bank.customers.web;

import com.alejandro.bank.customers.domain.Customer;
import com.alejandro.bank.customers.domain.CustomerStatus;
import com.alejandro.bank.customers.exception.CustomerAlreadyExistsException;
import com.alejandro.bank.customers.service.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import com.alejandro.bank.customers.exception.CustomerNotFoundException;
import com.alejandro.bank.customers.domain.DocumentType;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(CustomerController.class)
class CustomerControllerTest {

    private static final String VALID_REQUEST = """
            {
              "documentNumber": "12345678",
              "firstName": "Ana María",
              "paternalLastName": "Quispe",
              "maternalLastName": "Rojas",
              "birthDate": "1995-04-12",
              "email": "ana@mail.com",
              "phone": "+51987654321"
            }
            """;

    @Autowired
    WebTestClient webTestClient;

    @MockitoBean
    CustomerService customerService;

    @Test
    void registerReturns201WithLocation() {
        when(customerService.register(any(Customer.class))).thenAnswer(invocation -> {
            Customer customer = invocation.getArgument(0);
            customer.setId("abc-123");
            customer.setStatus(CustomerStatus.ACTIVE);
            customer.setCreatedAt(Instant.parse("2026-10-23T10:00:00Z"));
            return Mono.just(customer);
        });

        webTestClient.post().uri("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(VALID_REQUEST)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().location("/customers/abc-123")
                .expectBody()
                .jsonPath("$.id").isEqualTo("abc-123")
                .jsonPath("$.status").isEqualTo("ACTIVE")
                .jsonPath("$.emails[0].address").isEqualTo("ana@mail.com")
                .jsonPath("$.emails[0].primary").isEqualTo(true);
    }

    @Test
    void registerWithInvalidDataReturns400WithEachField() {
        String invalidRequest = VALID_REQUEST
                .replace("\"12345678\"", "\"123\"")
                .replace("\"ana@mail.com\"", "\"no-es-email\"");

        webTestClient.post().uri("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(invalidRequest)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.errors.length()").isEqualTo(2)
                .jsonPath("$.errors[?(@.field == 'documentNumber')]").exists()
                .jsonPath("$.errors[?(@.field == 'email')]").exists();

        verify(customerService, never()).register(any());
    }

    @Test
    void registerWithDuplicatedDocumentReturns409() {
        when(customerService.register(any(Customer.class)))
                .thenReturn(Mono.error(new CustomerAlreadyExistsException()));

        webTestClient.post().uri("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(VALID_REQUEST)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Ya existe un cliente registrado con ese documento");
    }

    @Test
    void findByIdReturns200WithoutInternalFields() {
        Customer customer = Customer.builder()
                .id("abc-123")
                .documentKey("DNI#12345678")
                .documentType(DocumentType.DNI)
                .documentNumber("12345678")
                .firstName("Ana María")
                .searchName("ana maria")
                .status(CustomerStatus.ACTIVE)
                .createdAt(Instant.parse("2026-10-23T10:00:00Z"))
                .build();
        when(customerService.findById("abc-123")).thenReturn(Mono.just(customer));

        webTestClient.get().uri("/customers/abc-123")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("abc-123")
                .jsonPath("$.firstName").isEqualTo("Ana María")
                .jsonPath("$.documentKey").doesNotExist()
                .jsonPath("$.searchName").doesNotExist()
                .jsonPath("$._etag").doesNotExist()
                .jsonPath("$._ts").doesNotExist();
    }

    @Test
    void findByIdWithUnknownIdReturns404() {
        when(customerService.findById("no-existe"))
                .thenReturn(Mono.error(new CustomerNotFoundException("no-existe")));

        webTestClient.get().uri("/customers/no-existe")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("No existe un cliente con id no-existe");
    }
    @Test
    void findByDniReturns200() {
        when(customerService.findByDni("12345678")).thenReturn(Mono.just(ana()));

        webTestClient.get().uri("/customers?dni=12345678")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("abc-123")
                .jsonPath("$.documentNumber").isEqualTo("12345678");
    }

    @Test
    void findByDniWithUnknownDniReturns404() {
        when(customerService.findByDni("87654321")).thenReturn(Mono.error(new CustomerNotFoundException()));

        webTestClient.get().uri("/customers?dni=87654321")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("No existe un cliente con ese documento");
    }

    @Test
    void findByDniWithInvalidFormatReturns400() {
        webTestClient.get().uri("/customers?dni=123")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.errors[0].field").isEqualTo("dni")
                .jsonPath("$.errors[0].message").isEqualTo("debe tener 8 dígitos");

        verify(customerService, never()).findByDni(any());
    }

    @Test
    void searchByNameReturnsList() {
        when(customerService.searchByName("quispe", 0, 20)).thenReturn(Flux.just(ana()));

        webTestClient.get().uri("/customers/search?name=quispe")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].firstName").isEqualTo("Ana María");
    }
    @Test
    void searchByNameWithoutMatchesReturnsEmptyList() {
        when(customerService.searchByName("zzz", 0, 20)).thenReturn(Flux.empty());

        webTestClient.get().uri("/customers/search?name=zzz")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void searchByNameWithSizeOver20Returns400() {
        webTestClient.get().uri("/customers/search?name=quispe&size=50")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].field").isEqualTo("size")
                .jsonPath("$.errors[0].message").isEqualTo("no puede ser mayor que 20");

        verify(customerService, never()).searchByName(any(), anyInt(), anyInt());
    }

    private Customer ana() {
        return Customer.builder()
                .id("abc-123")
                .documentType(DocumentType.DNI)
                .documentNumber("12345678")
                .firstName("Ana María")
                .status(CustomerStatus.ACTIVE)
                .build();
    }
}