package com.alejandro.bank.customers.cache;

import com.alejandro.bank.customers.domain.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerCacheTest {

    @Mock
    ReactiveRedisTemplate<String, Customer> customerRedisTemplate;

    @Mock
    ReactiveValueOperations<String, Customer> valueOperations;

    @InjectMocks
    CustomerCache customerCache;

    @BeforeEach
    void setUp() {
        lenient().when(customerRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void getUsesVersionedKey() {
        Customer cached = Customer.builder().id("abc-123").build();
        when(valueOperations.get("customers:v2:abc-123")).thenReturn(Mono.just(cached));

        StepVerifier.create(customerCache.get("abc-123"))
                .expectNext(cached)
                .verifyComplete();
    }

    @Test
    void getWithRedisDownBehavesAsMiss() {
        when(valueOperations.get(any())).thenReturn(Mono.error(new RedisConnectionFailureException("Redis caído")));

        StepVerifier.create(customerCache.get("abc-123"))
                .verifyComplete();
    }

    @Test
    void putStoresWithTtl() {
        Customer customer = Customer.builder().id("abc-123").build();
        when(valueOperations.set("customers:v2:abc-123", customer, CustomerCache.TTL)).thenReturn(Mono.just(true));

        StepVerifier.create(customerCache.put(customer))
                .verifyComplete();

        verify(valueOperations).set("customers:v2:abc-123", customer, CustomerCache.TTL);
    }

    @Test
    void putWithRedisDownDoesNotFail() {
        Customer customer = Customer.builder().id("abc-123").build();
        when(valueOperations.set(any(), any(), any())).thenReturn(Mono.error(new RedisConnectionFailureException("Redis caído")));

        StepVerifier.create(customerCache.put(customer))
                .verifyComplete();
    }

    @Test
    void evictDeletesVersionedKey() {
        when(customerRedisTemplate.delete("customers:v2:abc-123")).thenReturn(Mono.just(1L));

        StepVerifier.create(customerCache.evict("abc-123"))
                .verifyComplete();

        verify(customerRedisTemplate).delete("customers:v2:abc-123");
    }

    @Test
    void evictWithRedisDownDoesNotFail() {
        when(customerRedisTemplate.delete("customers:v2:abc-123"))
                .thenReturn(Mono.error(new RedisConnectionFailureException("Redis caído")));

        StepVerifier.create(customerCache.evict("abc-123"))
                .verifyComplete();
    }
}
