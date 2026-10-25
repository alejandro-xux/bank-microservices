package com.alejandro.bank.notifications.customers;

import com.alejandro.bank.notifications.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// BANK-71: el token de servicio de verdad, de un Keycloak real con el realm del repo (infra/keycloak/bank-realm.json):
// lleva el rol NOTIFICATIONS y la audiencia bank-api, y se reutiliza en vez de pedir uno por llamada
class ServiceTokenKeycloakTest extends IntegrationTest {

    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.8.1")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withCopyFileToContainer(MountableFile.forHostPath("../infra/keycloak/bank-realm.json"),
                    "/opt/keycloak/data/import/bank-realm.json")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(2)));

    static {
        KEYCLOAK.start();
    }

    @DynamicPropertySource
    static void tokenUri(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.bank-identity.token-uri", () ->
                "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/bank/protocol/openid-connect/token");
    }

    @Autowired
    CustomerContacts contacts;

    @Test
    void theServiceTokenHasOnlyTheNotificationsRoleAndTheBankApiAudience() {
        RECEIVED_AUTHORIZATIONS.clear();

        contacts.find(ANA);

        String claims = payload(RECEIVED_AUTHORIZATIONS.peek());
        assertThat(JsonPath.<String>read(claims, "$.azp")).isEqualTo("notifications-service");
        assertThat(JsonPath.<List<String>>read(claims, "$.roles")).containsExactly("NOTIFICATIONS");
        assertThat(claims).contains("\"aud\":\"bank-api\"");
    }

    @Test
    void theTokenIsReusedInsteadOfAskingKeycloakOnEveryCall() {
        RECEIVED_AUTHORIZATIONS.clear();

        for (int i = 0; i < 5; i++) {
            contacts.find(i % 2 == 0 ? ANA : LUIS);
        }

        // Un token nuevo tendría otro jti y otra firma: cinco llamadas, un solo token
        assertThat(RECEIVED_AUTHORIZATIONS).hasSize(5);
        assertThat(RECEIVED_AUTHORIZATIONS.stream().distinct()).hasSize(1);
    }

    private static String payload(String authorization) {
        String jwt = authorization.substring("Bearer ".length());
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }
}
