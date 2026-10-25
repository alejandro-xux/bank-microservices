package com.alejandro.bank.customers.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Cache del vínculo cuenta de acceso → cliente (BANK-65): misma clave versionada, mismo TTL que el de
// clientes, y Redis caído se comporta como un miss (nunca un error para el cliente)
@ExtendWith(MockitoExtension.class)
class IdentityCacheTest {

    @Mock
    ReactiveStringRedisTemplate redisTemplate;

    @Mock
    ReactiveValueOperations<String, String> valueOperations;

    @InjectMocks
    IdentityCache identityCache;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void getUsesVersionedKey() {
        when(valueOperations.get("identity:v1:oid-ana")).thenReturn(Mono.just("abc-123"));

        StepVerifier.create(identityCache.get("oid-ana"))
                .expectNext("abc-123")
                .verifyComplete();
    }

    @Test
    void putUsesTheSameTtlAsTheCustomerCache() {
        when(valueOperations.set("identity:v1:oid-ana", "abc-123", CustomerCache.TTL)).thenReturn(Mono.just(true));

        StepVerifier.create(identityCache.put("oid-ana", "abc-123")).verifyComplete();

        verify(valueOperations).set("identity:v1:oid-ana", "abc-123", CustomerCache.TTL);
    }

    @Test
    void redisDownIsAMissNotAnError() {
        when(valueOperations.get("identity:v1:oid-ana")).thenReturn(Mono.error(new RedisConnectionFailureException("caído")));
        when(valueOperations.set("identity:v1:oid-ana", "abc-123", CustomerCache.TTL))
                .thenReturn(Mono.error(new RedisConnectionFailureException("caído")));
        when(redisTemplate.delete("identity:v1:oid-ana")).thenReturn(Mono.error(new RedisConnectionFailureException("caído")));

        StepVerifier.create(identityCache.get("oid-ana")).verifyComplete();
        StepVerifier.create(identityCache.put("oid-ana", "abc-123")).verifyComplete();
        StepVerifier.create(identityCache.evict("oid-ana")).verifyComplete();
    }
}
