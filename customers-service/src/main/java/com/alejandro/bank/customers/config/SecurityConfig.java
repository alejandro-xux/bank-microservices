package com.alejandro.bank.customers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    // Roles del claim "roles" del token
    public static final String ADMIN = "ADMIN";
    public static final String CUSTOMER = "CUSTOMER";

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
                        // Registrar y buscar clientes: solo el asesor
                        .pathMatchers(HttpMethod.POST, "/customers").hasRole(ADMIN)
                        .pathMatchers(HttpMethod.GET, "/customers", "/customers/search").hasRole(ADMIN)
                        // /customers/{id}: asesor o cliente; que sea el dueño lo revisa CustomerAccessPolicy
                        .pathMatchers("/customers/**").hasAnyRole(ADMIN, CUSTOMER)
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
