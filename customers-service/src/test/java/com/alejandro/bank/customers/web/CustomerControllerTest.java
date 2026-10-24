package com.alejandro.bank.customers.web;

import com.alejandro.bank.customers.config.SecurityConfig;
import com.alejandro.bank.customers.domain.Customer;
import com.alejandro.bank.customers.domain.CustomerStatus;
import com.alejandro.bank.customers.domain.Email;
import com.alejandro.bank.customers.exception.CustomerAlreadyExistsException;
import com.alejandro.bank.customers.security.CustomerAccessPolicy;
import com.alejandro.bank.customers.service.CustomerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import com.alejandro.bank.customers.exception.CustomerModifiedException;
import com.alejandro.bank.customers.exception.CustomerNotFoundException;
import com.alejandro.bank.customers.exception.CustomerPreconditionFailedException;
import com.alejandro.bank.customers.domain.DocumentType;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(CustomerController.class)
@Import({SecurityConfig.class, CustomerAccessPolicy.class})
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

    private static final String ANA_OID = "4f8c2a1e-6b3d-4c9a-9e7f-2d5b8a1c3e01";

    // Cliente sin token, para probar el 401
    WebTestClient anonymousClient;
    // Ana con rol CUSTOMER: su oid es el identityId del cliente abc-123
    WebTestClient anaClient;

    // JWT simulado (sin Keycloak). Por defecto el asesor (ADMIN), que puede usar toda la API.
    @BeforeEach
    void authenticate() {
        anonymousClient = webTestClient;
        anaClient = webTestClient.mutateWith(mockJwt()
                .jwt(jwt -> jwt.claim("oid", ANA_OID))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        webTestClient = webTestClient.mutateWith(mockJwt()
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

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

    @Test
    void findByIdReturnsETagHeader() {
        Customer customer = ana();
        customer.setEtag("\"v1\"");
        when(customerService.findById("abc-123")).thenReturn(Mono.just(customer));

        webTestClient.get().uri("/customers/abc-123")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("ETag", "\"v1\"");
    }

    @Test
    void updateContactPassesIfMatchAndReturnsNewETag() {
        Customer updated = ana();
        updated.setEtag("\"v2\"");
        when(customerService.updateContact("abc-123", "nuevo@mail.com", null, "\"v1\"")).thenReturn(Mono.just(updated));

        webTestClient.patch().uri("/customers/abc-123")
                .header("If-Match", "\"v1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"nuevo@mail.com\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("ETag", "\"v2\"");
    }

    @Test
    void updateContactWithStaleIfMatchReturns412() {
        when(customerService.updateContact("abc-123", "nuevo@mail.com", null, "\"v0\""))
                .thenReturn(Mono.error(new CustomerPreconditionFailedException()));

        webTestClient.patch().uri("/customers/abc-123")
                .header("If-Match", "\"v0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"nuevo@mail.com\"}")
                .exchange()
                .expectStatus().isEqualTo(412)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Versión desactualizada");
    }

    @Test
    void updateContactModifiedConcurrentlyReturns409() {
        when(customerService.updateContact("abc-123", "nuevo@mail.com", null, null))
                .thenReturn(Mono.error(new CustomerModifiedException()));

        webTestClient.patch().uri("/customers/abc-123")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"nuevo@mail.com\"}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Conflicto de concurrencia");
    }

    private Customer ana() {
        return Customer.builder()
                .id("abc-123")
                .documentType(DocumentType.DNI)
                .documentNumber("12345678")
                .firstName("Ana María")
                .status(CustomerStatus.ACTIVE)
                .identityId(ANA_OID)
                .build();
    }

    private Customer luis() {
        return Customer.builder()
                .id("def-456")
                .documentType(DocumentType.DNI)
                .documentNumber("87654321")
                .firstName("Luis")
                .status(CustomerStatus.ACTIVE)
                .identityId("otro-oid")
                .build();
    }

    @Test
    void updateContactReturns200AndIgnoresOtherFields() {
        Customer updated = ana();
        updated.setEmails(List.of(new Email("nuevo@mail.com", Email.Type.PERSONAL, true, false)));
        updated.setUpdatedAt(Instant.parse("2026-10-23T12:00:00Z"));
        when(customerService.updateContact("abc-123", "nuevo@mail.com", null, null)).thenReturn(Mono.just(updated));

        webTestClient.patch().uri("/customers/abc-123")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"nuevo@mail.com\",\"firstName\":\"Hacker\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.emails[0].address").isEqualTo("nuevo@mail.com")
                .jsonPath("$.firstName").isEqualTo("Ana María")
                .jsonPath("$.updatedAt").isEqualTo("2026-10-23T12:00:00Z");

        verify(customerService).updateContact(eq("abc-123"), eq("nuevo@mail.com"), isNull(), isNull());
    }

    @Test
    void updateContactWithEmptyBodyReturns400() {
        webTestClient.patch().uri("/customers/abc-123")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].message").isEqualTo("debe enviar email o teléfono");
    }

    @Test
    void updateContactWithInvalidEmailReturns400() {
        webTestClient.patch().uri("/customers/abc-123")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"no-es-email\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].field").isEqualTo("email")
                .jsonPath("$.errors[0].message").isEqualTo("debe ser un email válido");

        verify(customerService, never()).updateContact(any(), any(), any(), any());
    }

    @Test
    void updateContactWithUnknownIdReturns404() {
        when(customerService.updateContact("no-existe", "nuevo@mail.com", null, null))
                .thenReturn(Mono.error(new CustomerNotFoundException("no-existe")));

        webTestClient.patch().uri("/customers/no-existe")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"nuevo@mail.com\"}")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void getWithoutTokenReturns401() {
        anonymousClient.get().uri("/customers/abc-123")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueMatches(HttpHeaders.WWW_AUTHENTICATE, "Bearer.*");

        verify(customerService, never()).findById(any());
    }

    @Test
    void patchWithoutTokenReturns401NotCsrf403() {
        anonymousClient.patch().uri("/customers/abc-123")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"nuevo@mail.com\"}")
                .exchange()
                .expectStatus().isUnauthorized();

        verify(customerService, never()).updateContact(any(), any(), any(), any());
    }

    @Test
    void customerReadsOwnDataReturns200() {
        when(customerService.findById("abc-123")).thenReturn(Mono.just(ana()));

        anaClient.get().uri("/customers/abc-123")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("abc-123");
    }

    @Test
    void customerReadingAnotherCustomerReturns403() {
        when(customerService.findById("def-456")).thenReturn(Mono.just(luis()));

        anaClient.get().uri("/customers/def-456")
                .exchange()
                .expectStatus().isForbidden();
    }

    // El mismo 403 que con un cliente ajeno: no revela si el id existe
    @Test
    void customerReadingUnknownIdReturns403Not404() {
        when(customerService.findById("no-existe"))
                .thenReturn(Mono.error(new CustomerNotFoundException("no-existe")));

        anaClient.get().uri("/customers/no-existe")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().isEmpty();
    }

    @Test
    void customerUpdatesOwnContactReturns200() {
        when(customerService.findById("abc-123")).thenReturn(Mono.just(ana()));
        when(customerService.updateContact("abc-123", "nuevo@mail.com", null, null)).thenReturn(Mono.just(ana()));

        anaClient.patch().uri("/customers/abc-123")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"nuevo@mail.com\"}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void customerUpdatingAnotherCustomerReturns403WithoutUpdating() {
        when(customerService.findById("def-456")).thenReturn(Mono.just(luis()));

        anaClient.patch().uri("/customers/def-456")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"nuevo@mail.com\"}")
                .exchange()
                .expectStatus().isForbidden();

        verify(customerService, never()).updateContact(any(), any(), any(), any());
    }

    @Test
    void customerCannotSearchByDni() {
        anaClient.get().uri("/customers?dni=87654321")
                .exchange()
                .expectStatus().isForbidden();

        verify(customerService, never()).findByDni(any());
    }

    @Test
    void customerCannotSearchByName() {
        anaClient.get().uri("/customers/search?name=quispe")
                .exchange()
                .expectStatus().isForbidden();

        verify(customerService, never()).searchByName(any(), anyInt(), anyInt());
    }

    @Test
    void customerCannotRegister() {
        anaClient.post().uri("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(VALID_REQUEST)
                .exchange()
                .expectStatus().isForbidden();

        verify(customerService, never()).register(any());
    }

    @Test
    void adminReadsAnyCustomer() {
        when(customerService.findById("def-456")).thenReturn(Mono.just(luis()));

        webTestClient.get().uri("/customers/def-456")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.firstName").isEqualTo("Luis");
    }

    @Test
    void tokenWithoutRoleReturns403() {
        anonymousClient.mutateWith(mockJwt()).get().uri("/customers/abc-123")
                .exchange()
                .expectStatus().isForbidden();

        verify(customerService, never()).findById(any());
    }

    @Test
    void customerReadsMeReturns200WithETag() {
        Customer customer = ana();
        customer.setEtag("\"v1\"");
        when(customerService.findByIdentityId(ANA_OID)).thenReturn(Mono.just(customer));

        anaClient.get().uri("/customers/me")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("ETag", "\"v1\"")
                .expectBody()
                .jsonPath("$.id").isEqualTo("abc-123");

        // "me" no se interpreta como un id
        verify(customerService, never()).findById(any());
    }

    @Test
    void customerWithoutLinkedCustomerReadingMeReturns403() {
        when(customerService.findByIdentityId(ANA_OID)).thenReturn(Mono.empty());

        anaClient.get().uri("/customers/me")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void adminReadingMeReturns403() {
        webTestClient.get().uri("/customers/me")
                .exchange()
                .expectStatus().isForbidden();

        verify(customerService, never()).findByIdentityId(any());
    }

    @Test
    void meWithoutTokenReturns401() {
        anonymousClient.get().uri("/customers/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
