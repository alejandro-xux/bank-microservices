package com.alejandro.bank.customers.web;

import com.alejandro.bank.customers.config.SecurityConfig;
import com.alejandro.bank.customers.exception.CustomerAlreadyExistsException;
import com.alejandro.bank.customers.exception.CustomerNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import org.springframework.context.annotation.Import;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(controllers = GlobalExceptionHandlerTest.FakeController.class)
@Import({GlobalExceptionHandlerTest.FakeController.class, SecurityConfig.class})
class GlobalExceptionHandlerTest {

    @Autowired
    WebTestClient webTestClient;

    @BeforeEach
    void authenticate() {
        webTestClient = webTestClient.mutateWith(mockJwt());
    }

    @Test
    void validationErrorReturns400WithEachField() {
        webTestClient.post().uri("/fake/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"\",\"email\":\"no-es-email\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Datos inválidos")
                .jsonPath("$.errors.length()").isEqualTo(2)
                .jsonPath("$.errors[?(@.field == 'email')]").exists()
                .jsonPath("$.errors[?(@.field == 'name')]").exists();
    }

    @Test
    void notFoundReturns404() {
        webTestClient.get().uri("/fake/not-found")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.detail").isEqualTo("No existe un cliente con id abc");
    }
    @Test
    void conflictReturns409() {
        webTestClient.get().uri("/fake/conflict")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Conflicto");
    }

    @RestController
    static class FakeController {

        record FakeRequest(@NotBlank String name, @Email String email) {
        }

        @PostMapping("/fake/validation")
        Mono<Void> validation(@Valid @RequestBody FakeRequest request) {
            return Mono.empty();
        }

        @GetMapping("/fake/not-found")
        Mono<Void> notFound() {
            return Mono.error(new CustomerNotFoundException("abc"));
        }

        @GetMapping("/fake/conflict")
        Mono<Void> conflict() {
            return Mono.error(new CustomerAlreadyExistsException());
        }
    }
}