package com.alejandro.bank.customers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    // Rutas sin token: probes de AKS y documentación de la API
    private static final String[] PUBLIC_PATHS = {
            "/actuator/health/**",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/webjars/swagger-ui/**"
    };

    // API stateless: cada petición trae su JWT (Bearer), sin sesión ni cookies, por eso CSRF no aplica
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(PUBLIC_PATHS).permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
