package com.alejandro.bank.customers.security;

import com.alejandro.bank.customers.domain.Customer;
import com.alejandro.bank.customers.exception.CustomerNotFoundException;
import com.alejandro.bank.customers.service.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerAccessPolicyTest {

    private static final String ANA_OID = "4f8c2a1e-6b3d-4c9a-9e7f-2d5b8a1c3e01";

    private final CustomerService customerService = mock(CustomerService.class);
    private final CustomerAccessPolicy policy = new CustomerAccessPolicy(customerService);

    @Test
    void adminAccessesAnyCustomerWithoutReadingIt() {
        StepVerifier.create(policy.checkAccess("def-456", token(null, "ROLE_ADMIN")))
                .verifyComplete();

        verify(customerService, never()).findById(any());
    }

    @Test
    void ownerCustomerIsAllowed() {
        when(customerService.findById("abc-123")).thenReturn(Mono.just(customer(ANA_OID)));

        StepVerifier.create(policy.checkAccess("abc-123", token(ANA_OID, "ROLE_CUSTOMER")))
                .verifyComplete();
    }

    @Test
    void anotherCustomersDataIsDenied() {
        when(customerService.findById("def-456")).thenReturn(Mono.just(customer("otro-oid")));

        StepVerifier.create(policy.checkAccess("def-456", token(ANA_OID, "ROLE_CUSTOMER")))
                .verifyError(AccessDeniedException.class);
    }

    @Test
    void customerWithoutLinkedIdentityIsDenied() {
        when(customerService.findById("def-456")).thenReturn(Mono.just(customer(null)));

        StepVerifier.create(policy.checkAccess("def-456", token(ANA_OID, "ROLE_CUSTOMER")))
                .verifyError(AccessDeniedException.class);
    }

    @Test
    void unknownIdIsDeniedNotNotFound() {
        when(customerService.findById("no-existe")).thenReturn(Mono.error(new CustomerNotFoundException("no-existe")));

        StepVerifier.create(policy.checkAccess("no-existe", token(ANA_OID, "ROLE_CUSTOMER")))
                .verifyError(AccessDeniedException.class);
    }

    @Test
    void customerTokenWithoutOidIsDenied() {
        StepVerifier.create(policy.checkAccess("abc-123", token(null, "ROLE_CUSTOMER")))
                .verifyError(AccessDeniedException.class);

        verify(customerService, never()).findById(any());
    }

    @Test
    void tokenWithoutRoleIsDenied() {
        StepVerifier.create(policy.checkAccess("abc-123", token(ANA_OID)))
                .verifyError(AccessDeniedException.class);

        verify(customerService, never()).findById(any());
    }

    @Test
    void ownCustomerIsFoundByOid() {
        Customer ana = customer(ANA_OID);
        when(customerService.findByIdentityId(ANA_OID)).thenReturn(Mono.just(ana));

        StepVerifier.create(policy.ownCustomer(token(ANA_OID, "ROLE_CUSTOMER")))
                .expectNext(ana)
                .verifyComplete();
    }

    @Test
    void ownCustomerWithoutLinkedCustomerIsDenied() {
        when(customerService.findByIdentityId(ANA_OID)).thenReturn(Mono.empty());

        StepVerifier.create(policy.ownCustomer(token(ANA_OID, "ROLE_CUSTOMER")))
                .verifyError(AccessDeniedException.class);
    }

    // El asesor no tiene ficha de cliente
    @Test
    void ownCustomerForAdminIsDeniedWithoutQuerying() {
        StepVerifier.create(policy.ownCustomer(token("7b2e9d4c-1a5f-4e8b-b3c6-9f0a2d4e6b02", "ROLE_ADMIN")))
                .verifyError(AccessDeniedException.class);

        verify(customerService, never()).findByIdentityId(any());
    }

    @Test
    void ownCustomerWithoutOidIsDenied() {
        StepVerifier.create(policy.ownCustomer(token(null, "ROLE_CUSTOMER")))
                .verifyError(AccessDeniedException.class);

        verify(customerService, never()).findByIdentityId(any());
    }

    private static Authentication token(String oid, String... authorities) {
        Jwt.Builder jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("user");
        if (oid != null) {
            jwt.claim("oid", oid);
        }
        return new JwtAuthenticationToken(jwt.build(), AuthorityUtils.createAuthorityList(authorities));
    }

    private static Customer customer(String identityId) {
        return Customer.builder().id("abc-123").identityId(identityId).build();
    }
}
