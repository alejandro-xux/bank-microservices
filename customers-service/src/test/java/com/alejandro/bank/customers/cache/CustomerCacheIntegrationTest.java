package com.alejandro.bank.customers.cache;

import com.alejandro.bank.customers.domain.Customer;
import com.alejandro.bank.customers.domain.CustomerStatus;
import com.alejandro.bank.customers.domain.DocumentType;
import com.alejandro.bank.customers.domain.Email;
import com.alejandro.bank.customers.domain.Phone;
import com.alejandro.bank.customers.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

// Cache Aside contra un Redis real en Docker (Testcontainers). Cosmos sigue simulado: no necesita credenciales.
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureWebTestClient
@Testcontainers
class CustomerCacheIntegrationTest {

    private static final String ID = "abc-123";
    private static final String KEY = "customers:v2:" + ID;

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.8.3-alpine").withExposedPorts(6379);

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    ReactiveStringRedisTemplate redisTemplate;

    @MockitoBean
    CustomerRepository customerRepository;

    @BeforeEach
    void cleanRedisAndAuthenticate() {
        redisTemplate.delete(KEY).block();
        webTestClient = webTestClient.mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void getStoresCustomerInRedisWithTtlAndSecondGetIsServedFromCache() {
        when(customerRepository.findById(ID)).thenReturn(Mono.just(ana()));

        String first = getCustomer();

        assertThat(redisTemplate.hasKey(KEY).block()).isTrue();
        assertThat(redisTemplate.getExpire(KEY).block())
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofMinutes(10));

        String second = getCustomer();

        assertThat(second).isEqualTo(first);
        verify(customerRepository, times(1)).findById(ID);
    }

    @Test
    void patchEvictsCachedCustomer() {
        when(customerRepository.findById(ID)).thenReturn(Mono.just(ana()));
        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        getCustomer();
        assertThat(redisTemplate.hasKey(KEY).block()).isTrue();

        webTestClient.patch().uri("/customers/" + ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"nuevo@mail.com\"}")
                .exchange()
                .expectStatus().isOk();

        assertThat(redisTemplate.hasKey(KEY).block()).isFalse();
    }

    private String getCustomer() {
        return webTestClient.get().uri("/customers/" + ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private Customer ana() {
        return Customer.builder()
                .id(ID)
                .documentKey("DNI#12345678")
                .documentType(DocumentType.DNI)
                .documentNumber("12345678")
                .firstName("Ana María")
                .paternalLastName("Quispe")
                .maternalLastName("Rojas")
                .birthDate(LocalDate.of(1995, 4, 12))
                .emails(List.of(new Email("ana@mail.com", Email.Type.PERSONAL, true, true)))
                .phones(List.of(new Phone("+51987654321", Phone.Type.MOBILE, true, true)))
                .status(CustomerStatus.ACTIVE)
                .createdAt(Instant.parse("2026-10-23T18:13:15Z"))
                .updatedAt(Instant.parse("2026-10-23T18:13:15Z"))
                .build();
    }
}
