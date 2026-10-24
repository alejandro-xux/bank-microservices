package com.alejandro.bank.customers.config;

import com.alejandro.bank.customers.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

// Aplicación completa sin token: lo público responde y la API exige autenticación
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureWebTestClient
class SecurityConfigTest {

    @Autowired
    WebTestClient webTestClient;

    @MockitoBean
    CustomerRepository customerRepository;

    @Test
    void livenessProbeIsPublic() {
        webTestClient.get().uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void openApiDocsArePublic() {
        webTestClient.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void customersApiRequiresToken() {
        webTestClient.get().uri("/customers/abc-123")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
