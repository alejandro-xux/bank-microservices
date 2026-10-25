package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.security.ProblemDetailSecurityHandlers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void healthIsPublicAndIncludesTheDatabase() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void readinessIncludesTheDatabase() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void withoutTokenIs401WithProblemDetail() throws Exception {
        mvc.perform(get("/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("No autenticado"))
                .andExpect(jsonPath("$.instance").value("/accounts"));
    }

    @Test
    void invalidTokenIs401WithProblemDetail() throws Exception {
        when(jwtDecoder.decode("token-falso")).thenThrow(new BadJwtException("firma inválida"));

        mvc.perform(get("/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer token-falso"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("No autenticado"));
    }

    @Test
    void validTokenPassesSecurity() throws Exception {
        when(jwtDecoder.decode("token-de-ana")).thenReturn(Jwt.withTokenValue("token-de-ana")
                .header("alg", "RS256")
                .subject("ana")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build());

        // Aún no hay endpoints (llegan en BANK-53): pasar la seguridad significa 404, no 401
        mvc.perform(get("/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer token-de-ana"))
                .andExpect(status().isNotFound());
    }

    @Test
    void accessDeniedIs403WithProblemDetail() throws Exception {
        // Ninguna ruta exige rol todavía: se prueba el handler que usará SecurityConfig
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ProblemDetailSecurityHandlers(jsonMapper).accessDeniedHandler()
                .handle(request, response, new AccessDeniedException("sin rol"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString())
                .contains("\"title\":\"Acceso denegado\"", "\"instance\":\"/accounts/123\"");
    }
}
