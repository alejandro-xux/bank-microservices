package com.alejandro.bank.customers.security;

import com.alejandro.bank.customers.cache.CustomerCache;
import com.alejandro.bank.customers.domain.Customer;
import com.alejandro.bank.customers.domain.CustomerStatus;
import com.alejandro.bank.customers.domain.DocumentType;
import com.alejandro.bank.customers.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

// Seguridad de punta a punta con un Keycloak real en Docker (Testcontainers) y el realm versionado del repo.
// A diferencia de mockJwt(), aquí se validan de verdad la firma, iss, aud y la conversión del claim "roles".
// Cosmos y Redis están simulados: no necesita credenciales de Azure.
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureWebTestClient
@Testcontainers
class KeycloakSecurityIntegrationTest {

    // Ids fijos del realm bank-realm.json (claim oid de cada usuario)
    private static final String ANA_OID = "4f8c2a1e-6b3d-4c9a-9e7f-2d5b8a1c3e01";
    private static final String ANA_ID = "c15a442d-5146-471e-8804-746f4b42253b";
    private static final String OTHER_ID = "08d38a3b-c398-48fb-be36-3d6a1dde7ad2";

    @Container
    static GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.8.1")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withCopyFileToContainer(MountableFile.forHostPath("../infra/keycloak/bank-realm.json"),
                    "/opt/keycloak/data/import/bank-realm.json")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(2)));

    // El servicio confía en este Keycloak: el iss de los tokens es esta misma URL
    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KeycloakSecurityIntegrationTest::issuerUri);
    }

    @Autowired
    WebTestClient webTestClient;

    @MockitoBean
    CustomerRepository customerRepository;

    @MockitoBean
    CustomerCache customerCache;

    @BeforeEach
    void cosmosAndCacheSimulated() {
        when(customerCache.get(anyString())).thenReturn(Mono.empty());
        when(customerCache.put(any())).thenReturn(Mono.empty());
        when(customerRepository.findById(ANA_ID)).thenReturn(Mono.just(customer(ANA_ID, ANA_OID)));
        when(customerRepository.findById(OTHER_ID)).thenReturn(Mono.just(customer(OTHER_ID, null)));
        when(customerRepository.findByIdentityId(ANA_OID)).thenReturn(Flux.just(customer(ANA_ID, ANA_OID)));
        when(customerRepository.searchByName(anyString(), anyInt(), anyInt())).thenReturn(Flux.empty());
    }

    @Test
    void customerReadsOwnDataWithRealToken() {
        get("/customers/" + ANA_ID, token("ana", "ana-local"))
                .expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(ANA_ID);
    }

    @Test
    void customerReadingAnotherCustomerWithRealTokenReturns403() {
        get("/customers/" + OTHER_ID, token("ana", "ana-local"))
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // El claim "roles" del token real se convierte en ROLE_CUSTOMER / ROLE_ADMIN
    @Test
    void customerRoleFromRealTokenCannotSearch() {
        get("/customers/search?name=ana", token("ana", "ana-local"))
                .expectStatus().isForbidden();
    }

    @Test
    void adminRoleFromRealTokenCanSearch() {
        get("/customers/search?name=ana", token("asesor", "asesor-local"))
                .expectStatus().isOk();
    }

    // El claim oid del token real lo pone el mapper del realm
    @Test
    void meResolvesCustomerFromRealOid() {
        get("/customers/me", token("ana", "ana-local"))
                .expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(ANA_ID);
    }

    @Test
    void tamperedSignatureReturns401() {
        String token = token("ana", "ana-local");
        int i = token.length() - 10;
        String tampered = token.substring(0, i) + (token.charAt(i) == 'A' ? 'B' : 'A') + token.substring(i + 1);

        get("/customers/" + ANA_ID, tampered)
                .expectStatus().isUnauthorized();
    }

    private WebTestClient.ResponseSpec get(String uri, String token) {
        return webTestClient.get().uri(uri)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange();
    }

    // Token real pedido a Keycloak con el cliente bank-cli (password grant, solo local)
    private static String token(String username, String password) {
        Map<?, ?> response = WebClient.create().post()
                .uri(issuerUri() + "/protocol/openid-connect/token")
                .body(BodyInserters.fromFormData("grant_type", "password")
                        .with("client_id", "bank-cli")
                        .with("username", username)
                        .with("password", password))
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        return (String) response.get("access_token");
    }

    private static String issuerUri() {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080) + "/realms/bank";
    }

    private static Customer customer(String id, String identityId) {
        return Customer.builder()
                .id(id)
                .documentType(DocumentType.DNI)
                .documentNumber("12345678")
                .firstName("Ana María")
                .status(CustomerStatus.ACTIVE)
                .identityId(identityId)
                .build();
    }
}
