package com.alejandro.bank.customers.config;

import org.springframework.context.annotation.Bean;
import com.alejandro.bank.customers.security.ProblemDetailSecurityHandlers;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import tools.jackson.databind.json.JsonMapper;

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
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, JsonMapper jsonMapper) {
        ProblemDetailSecurityHandlers handlers = new ProblemDetailSecurityHandlers(jsonMapper);
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(PUBLIC_PATHS).permitAll()
                        // Registrar y buscar clientes: solo el asesor
                        .pathMatchers(HttpMethod.POST, "/customers").hasRole(ADMIN)
                        .pathMatchers(HttpMethod.GET, "/customers", "/customers/search").hasRole(ADMIN)
                        // Vincular/desvincular la cuenta de acceso: solo el asesor. Sin esta regla, /customers/**
                        // dejaría a un CUSTOMER unir su cuenta a la ficha de otro cliente (BANK-48)
                        .pathMatchers(HttpMethod.PUT, "/customers/*/identity").hasRole(ADMIN)
                        .pathMatchers(HttpMethod.DELETE, "/customers/*/identity").hasRole(ADMIN)
                        // Mis datos: solo un cliente (el asesor no tiene ficha de cliente)
                        .pathMatchers(HttpMethod.GET, "/customers/me").hasRole(CUSTOMER)
                        // /customers/{id}: asesor o cliente; que sea el dueño lo revisa CustomerAccessPolicy
                        .pathMatchers("/customers/**").hasAnyRole(ADMIN, CUSTOMER)
                        .anyExchange().authenticated())
                // 401 y 403 con cuerpo ProblemDetail: sin token y accesos denegados (rol, dueño, /me)...
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(handlers.authenticationEntryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()))
                // ...y token inválido, vencido o de otra audiencia
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(handlers.authenticationEntryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()))
                .build();
    }
}
