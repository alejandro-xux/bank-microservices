package com.alejandro.bank.customers.web;

import com.alejandro.bank.customers.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
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

    @GetMapping("/{id}")
    @Operation(summary = "Consultar los datos de un cliente")
    @ApiResponse(responseCode = "200", description = "Cliente encontrado")
    @ApiResponse(responseCode = "404", description = "No existe un cliente con ese id")
    public Mono<CustomerResponse> findById(@PathVariable String id) {
        return customerService.findById(id).map(CustomerResponse::from);
    }

    @GetMapping(params = "dni")
    @Operation(summary = "Buscar un cliente por DNI")
    @ApiResponse(responseCode = "200", description = "Cliente encontrado")
    @ApiResponse(responseCode = "400", description = "DNI con formato inválido")
    @ApiResponse(responseCode = "404", description = "No existe un cliente con ese DNI")
    public Mono<CustomerResponse> findByDni(
            @RequestParam @Pattern(regexp = "\\d{8}", message = "debe tener 8 dígitos") String dni) {
        return customerService.findByDni(dni).map(CustomerResponse::from);
    }

    @GetMapping(path = "/search", params = "name")
    @Operation(summary = "Buscar clientes por nombre o apellidos (paginado)")
    @ApiResponse(responseCode = "200", description = "Lista de clientes (vacía si no hay coincidencias)")
    @ApiResponse(responseCode = "400", description = "Parámetros inválidos")
    public Flux<CustomerResponse> searchByName(
            @RequestParam @Size(min = 2, max = 100, message = "debe tener entre 2 y 100 caracteres") String name,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "no puede ser negativo") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "debe ser al menos 1")
            @Max(value = 20, message = "no puede ser mayor que 20") int size) {
        return customerService.searchByName(name, page, size).map(CustomerResponse::from);
    }
}