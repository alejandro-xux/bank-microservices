package com.alejandro.bank.customers.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

// Cache Aside del vínculo cuenta de acceso → cliente (BANK-65): identity:v1:{oid} → id del cliente.
// Evita la consulta cross-partition a Cosmos de /customers/me (~250 ms) en cada operación de un cliente.
// Solo guarda el id: la ficha sigue saliendo de su propio cache (customers:v2:{id}), y quien lo usa
// comprueba que esa ficha siga vinculada a este oid. Si Redis falla, se comporta como un miss
@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityCache {

    private static final String KEY_PREFIX = "identity:v1:";

    private final ReactiveStringRedisTemplate redisTemplate;

    public Mono<String> get(String identityId) {
        return redisTemplate.opsForValue().get(key(identityId))
                .onErrorResume(ex -> {
                    log.warn("Redis no disponible al leer {}: {}", key(identityId), ex.getMessage());
                    return Mono.empty();
                });
    }

    // Mismo TTL que el cache de clientes
    public Mono<Void> put(String identityId, String customerId) {
        return redisTemplate.opsForValue().set(key(identityId), customerId, CustomerCache.TTL)
                .then()
                .onErrorResume(ex -> {
                    log.warn("Redis no disponible al guardar {}: {}", key(identityId), ex.getMessage());
                    return Mono.empty();
                });
    }

    public Mono<Void> evict(String identityId) {
        return redisTemplate.delete(key(identityId))
                .then()
                .onErrorResume(ex -> {
                    log.warn("Redis no disponible al invalidar {}: {}", key(identityId), ex.getMessage());
                    return Mono.empty();
                });
    }

    static String key(String identityId) {
        return KEY_PREFIX + identityId;
    }
}
