package com.alejandro.bank.notifications;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// Las pruebas que no son del token: el de servicio es simulado ("token-servicio") y no hace falta Keycloak.
// El token real se prueba en ServiceTokenKeycloakTest
public abstract class TokenMockedIntegrationTest extends IntegrationTest {

    @MockitoBean
    protected OAuth2AuthorizedClientManager serviceTokenManager;

    @BeforeEach
    void serviceToken() {
        RECEIVED_AUTHORIZATIONS.clear();
        ClientRegistration registration = ClientRegistration.withRegistrationId("bank-api").clientId("notifications-service")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).tokenUri("http://keycloak/token").build();
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token-servicio",
                Instant.now(), Instant.now().plusSeconds(300));
        when(serviceTokenManager.authorize(any())).thenReturn(new OAuth2AuthorizedClient(registration, "notifications-service", token));
    }
}
