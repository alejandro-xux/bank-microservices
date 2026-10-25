package com.alejandro.bank.accounts.customers;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// Token relay: cada llamada Feign lleva el MISMO Authorization de la petición que se está atendiendo,
// así el otro servicio vuelve a validar el token y decide con los roles del usuario (zero trust).
// Funciona porque en Spring MVC la llamada corre en el mismo hilo que la petición entrante
@Component
public class TokenRelayInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String authorization = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null) {
                template.header(HttpHeaders.AUTHORIZATION, authorization);
            }
        }
    }
}
