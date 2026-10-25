package com.alejandro.bank.accounts.config;

import com.alejandro.bank.accounts.security.ProblemDetailSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // Roles del claim "roles" del token
    public static final String ADMIN = "ADMIN";
    public static final String CUSTOMER = "CUSTOMER";

    // Rutas sin token: probes de AKS y documentación de la API
    private static final String[] PUBLIC_PATHS = {
            "/actuator/health/**",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**"
    };

    // API stateless: cada petición trae su JWT (Bearer), sin sesión ni cookies, por eso CSRF no aplica
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper jsonMapper) {
        ProblemDetailSecurityHandlers handlers = new ProblemDetailSecurityHandlers(jsonMapper);
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // Abrir cuentas y buscarlas por cliente: solo el asesor (BANK-53, BANK-54)
                        .requestMatchers(HttpMethod.POST, "/accounts").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.GET, "/accounts").hasRole(ADMIN)
                        // Depósito en ventanilla: solo el asesor (BANK-55)
                        .requestMatchers(HttpMethod.POST, "/accounts/*/deposits").hasRole(ADMIN)
                        // Mis cuentas: solo un cliente (el asesor no tiene ficha de cliente). Va antes de /accounts/*
                        .requestMatchers(HttpMethod.GET, "/accounts/me").hasRole(CUSTOMER)
                        // Una cuenta: asesor o cliente; que sea el dueño lo revisa AccountAccessPolicy
                        .requestMatchers(HttpMethod.GET, "/accounts/*").hasAnyRole(ADMIN, CUSTOMER)
                        // Transferir: asesor o cliente; que la cuenta origen sea suya lo revisa AccountAccessPolicy
                        .requestMatchers(HttpMethod.POST, "/transfers").hasAnyRole(ADMIN, CUSTOMER)
                        .anyRequest().authenticated())
                // 401 y 403 con cuerpo ProblemDetail: sin token y accesos denegados...
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
