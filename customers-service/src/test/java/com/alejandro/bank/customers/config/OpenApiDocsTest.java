package com.alejandro.bank.customers.config;

import com.alejandro.bank.customers.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// El OpenAPI generado: esquema de seguridad para Swagger y 401/403 documentados en todos los endpoints
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureWebTestClient
class OpenApiDocsTest {

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    JsonMapper jsonMapper;

    @MockitoBean
    CustomerRepository customerRepository;

    JsonNode apiDocs;

    @BeforeEach
    void loadApiDocs() {
        byte[] body = webTestClient.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        apiDocs = jsonMapper.readTree(body);
    }

    @Test
    void declaresOpenIdConnectSecuritySchemeForAuthorizeButton() {
        JsonNode scheme = apiDocs.at("/components/securitySchemes/oidc");
        assertThat(scheme.at("/type").asString()).isEqualTo("openIdConnect");
        assertThat(scheme.at("/openIdConnectUrl").asString())
                .isEqualTo("http://localhost:8180/realms/bank/.well-known/openid-configuration");
        assertThat(apiDocs.at("/security/0/oidc").isArray()).isTrue();
    }

    @Test
    void everyOperationDocuments401And403WithProblemDetail() {
        List<String> missing = new ArrayList<>();
        apiDocs.at("/paths").properties().forEach(path -> path.getValue().properties().forEach(operation -> {
            JsonNode responses = operation.getValue().at("/responses");
            for (String code : List.of("401", "403")) {
                if (responses.at("/" + code).isMissingNode()) {
                    missing.add(operation.getKey().toUpperCase() + " " + path.getKey() + " → " + code);
                }
            }
        }));

        assertThat(missing).isEmpty();
        assertThat(apiDocs.at("/components/schemas/ProblemDetail").isMissingNode()).isFalse();
    }

    @Test
    void keepsOwnDescriptionsOfOwnerRule() {
        assertThat(apiDocs.at("/paths/~1customers~1{id}/get/responses/403/description").asString())
                .isEqualTo("El cliente no es el dueño (o el id no existe)");
        assertThat(apiDocs.at("/paths/~1customers/post/responses/403/description").asString())
                .isEqualTo("No tiene permiso para acceder a este recurso");
    }
}
