package com.alejandro.bank.notifications.customers;

import com.alejandro.bank.notifications.TokenMockedIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// BANK-71: cómo se traduce cada respuesta de GetContact. El token es simulado; el real de Keycloak se prueba en
// ServiceTokenKeycloakTest
class CustomerContactsTest extends TokenMockedIntegrationTest {

    @Autowired
    CustomerContacts contacts;

    @Test
    void returnsTheContactAndSendsTheServiceToken() {
        assertThat(contacts.find(ANA)).contains(new CustomerContacts.Contact(ANA, "Ana Quispe", "ana@mail.com"));
        assertThat(RECEIVED_AUTHORIZATIONS).containsExactly("Bearer token-servicio");
    }

    @Test
    void anUnknownCustomerIsEmptyNotAnError() {
        // No hay a quién avisar: reintentar no lo arregla
        assertThat(contacts.find(MISSING)).isEmpty();
    }

    @Test
    void customersDownIsARetryableError() {
        assertThatThrownBy(() -> contacts.find(DOWN)).isInstanceOf(ContactsUnavailableException.class)
                .hasMessageContaining("UNAVAILABLE");
    }

    @Test
    void aSlowCustomersServiceFailsAtTheDeadline() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> contacts.find(SLOW)).isInstanceOf(ContactsUnavailableException.class)
                .hasMessageContaining("DEADLINE_EXCEEDED");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2900));
    }

    @Test
    void withoutATokenTheCallDoesNotEvenGoOutAndIsRetryable() {
        when(serviceTokenManager.authorize(any())).thenThrow(new OAuth2AuthorizationException(new OAuth2Error("server_error")));

        assertThatThrownBy(() -> contacts.find(ANA)).isInstanceOf(ContactsUnavailableException.class);
        assertThat(RECEIVED_AUTHORIZATIONS).isEmpty();
    }
}
