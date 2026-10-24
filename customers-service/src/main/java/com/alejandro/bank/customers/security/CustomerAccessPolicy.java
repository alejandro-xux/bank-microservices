package com.alejandro.bank.customers.security;

import com.alejandro.bank.customers.config.SecurityConfig;
import com.alejandro.bank.customers.exception.CustomerNotFoundException;
import com.alejandro.bank.customers.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

// Regla del dueño: el asesor (ADMIN) accede a cualquier cliente; un CUSTOMER solo al suyo (identityId == oid).
// Cliente ajeno o inexistente → el mismo 403, para no revelar qué ids existen (IDOR).
@Component
@RequiredArgsConstructor
public class CustomerAccessPolicy {

    private static final String ROLE_PREFIX = "ROLE_";

    private final CustomerService customerService;

    public Mono<Void> checkAccess(String customerId, Authentication authentication) {
        if (hasRole(authentication, SecurityConfig.ADMIN)) {
            return Mono.empty();
        }
        String oid = oid(authentication);
        if (oid == null || !hasRole(authentication, SecurityConfig.CUSTOMER)) {
            return Mono.error(denied());
        }
        // Lee el cliente pasando por el cache de Redis
        return customerService.findById(customerId)
                .onErrorResume(CustomerNotFoundException.class, ex -> Mono.empty())
                .filter(customer -> oid.equals(customer.getIdentityId()))
                .switchIfEmpty(Mono.error(denied()))
                .then();
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> (ROLE_PREFIX + role).equals(authority.getAuthority()));
    }

    private static String oid(Authentication authentication) {
        return authentication.getPrincipal() instanceof Jwt jwt ? jwt.getClaimAsString("oid") : null;
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Sin acceso al cliente");
    }
}
