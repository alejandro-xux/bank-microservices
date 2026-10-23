package com.alejandro.bank.customers.web;

import com.alejandro.bank.customers.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.net.URI;

@RestController
@RequestMapping("/customers")
@RequiredArgsConstructor
@Tag(name = "Customers", description = "Registro y consulta de clientes")
public class CustomerController {
    private final CustomerService customerService;

    @PostMapping
    @Operation(summary = "Registrar un cliente con su DNI")
    @ApiResponse(responseCode = "201", description = "Cliente creado")
    @ApiResponse(responseCode = "400", description = "Datos inválidos")
    @ApiResponse(responseCode = "409", description = "Ya existe un cliente con ese DNI")
    public Mono<ResponseEntity<CustomerResponse>> register(@Valid @RequestBody RegisterCustomerRequest request) {
        return customerService.register(request.toCustomer())
                .map(CustomerResponse::from)
                .map(body -> ResponseEntity
                        .created(URI.create("/customers/" + body.id()))
                        .body(body));
    }
}