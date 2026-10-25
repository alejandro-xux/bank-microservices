package com.alejandro.bank.notifications.customers;

import com.alejandro.bank.notifications.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// BANK-71: cómo se traduce cada respuesta de GetContact. El token es simulado; el real de Keycloak se prueba en
// ServiceTokenKeycloakTest
class CustomerContactsTest extends IntegrationTest {

    @MockitoBean
    OAuth2AuthorizedClientManager serviceTokenManager;

    @Autowired
    CustomerContacts contacts;

    @BeforeEach
    void serviceToken() {
        RECEIVED_AUTHORIZATIONS.clear();
        ClientRegistration registration = ClientRegistration.withRegistrationId("bank-api").clientId("notifications-service")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).tokenUri("http://keycloak/token").build();
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token-servicio",
                Instant.now(), Instant.now().plusSeconds(300));
        when(serviceTokenManager.authorize(any())).thenReturn(new OAuth2AuthorizedClient(registration, "notifications-service", token));
    }

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
