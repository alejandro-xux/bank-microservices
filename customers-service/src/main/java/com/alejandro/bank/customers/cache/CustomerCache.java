package com.alejandro.bank.customers.cache;

import com.alejandro.bank.customers.domain.Customer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

// Cache Aside de clientes en Redis. Si Redis falla, se comporta como un miss: el cache es una optimización.
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerCache {

    static final Duration TTL = Duration.ofMinutes(10);
    private static final String KEY_PREFIX = "customers:v1:";

    private final ReactiveRedisTemplate<String, Customer> customerRedisTemplate;

    public Mono<Customer> get(String id) {
        return customerRedisTemplate.opsForValue().get(key(id))
                .onErrorResume(ex -> {
                    log.warn("Redis no disponible al leer {}: {}", key(id), ex.getMessage());
                    return Mono.empty();
                });
    }

    public Mono<Void> put(Customer customer) {
        return customerRedisTemplate.opsForValue().set(key(customer.getId()), customer, TTL)
                .then()
                .onErrorResume(ex -> {
                    log.warn("Redis no disponible al guardar {}: {}", key(customer.getId()), ex.getMessage());
                    return Mono.empty();
                });
    }

    static String key(String id) {
        return KEY_PREFIX + id;
    }
}
