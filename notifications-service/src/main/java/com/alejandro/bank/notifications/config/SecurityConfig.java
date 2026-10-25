package com.alejandro.bank.notifications.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

// notifications-service no tiene API (BANK-70): solo las probes de actuator, sin token. Hace falta decirlo porque
// el cliente OAuth2 (BANK-71) trae Spring Security, y por defecto pediría usuario y contraseña en todo
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain actuatorOnly(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
