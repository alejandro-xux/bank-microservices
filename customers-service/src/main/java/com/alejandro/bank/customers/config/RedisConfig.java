package com.alejandro.bank.customers.config;

import com.alejandro.bank.customers.domain.Customer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class RedisConfig {

    // Claves como texto y valores como JSON, con el mismo JsonMapper de la API (fechas ISO)
    @Bean
    public ReactiveRedisTemplate<String, Customer> customerRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory, JsonMapper jsonMapper) {
        RedisSerializationContext<String, Customer> context = RedisSerializationContext
                .<String, Customer>newSerializationContext(new StringRedisSerializer())
                .value(new JacksonJsonRedisSerializer<>(jsonMapper, Customer.class))
                .build();
        return new ReactiveRedisTemplate<>(connectionFactory, context);
    }
}
