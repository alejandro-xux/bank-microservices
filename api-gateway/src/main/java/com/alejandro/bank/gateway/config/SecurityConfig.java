package com.alejandro.bank.gateway.config;

import com.alejandro.bank.gateway.security.ProblemDetailSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    // El gateway solo comprueba QUIÉN eres (token válido). Roles y dueño los decide cada servicio,
    // que además vuelve a validar el token (zero trust).
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, JsonMapper jsonMapper) {
        ProblemDetailSecurityHandlers handlers = new ProblemDetailSecurityHandlers(jsonMapper);
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges
                        // Probes de AKS
                        .pathMatchers("/actuator/health/**").permitAll()
                        .anyExchange().authenticated())
                // 401 en ProblemDetail sin llegar al servicio: sin token, o token inválido, vencido o de otra audiencia
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(handlers.authenticationEntryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(handlers.authenticationEntryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()))
                .build();
    }
}
