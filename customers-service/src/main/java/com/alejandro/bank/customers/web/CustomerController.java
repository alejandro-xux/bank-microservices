package com.alejandro.bank.customers.web;

import com.alejandro.bank.customers.domain.Customer;
import com.alejandro.bank.customers.security.CustomerAccessPolicy;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;

@RestController
@RequestMapping("/customers")
@RequiredArgsConstructor
@Tag(name = "Customers", description = "Registro y consulta de clientes")
public class CustomerController {
    private final CustomerService customerService;
    private final CustomerAccessPolicy accessPolicy;

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

    @GetMapping("/me")
    @Operation(summary = "Consultar mis datos (el cliente vinculado al token)")
    @ApiResponse(responseCode = "200", description = "Mis datos")
    @ApiResponse(responseCode = "403", description = "El token no es de un cliente o no tiene un cliente vinculado")
    public Mono<ResponseEntity<CustomerResponse>> findMe(Authentication authentication) {
        return accessPolicy.ownCustomer(authentication).map(CustomerController::withETag);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar los datos de un cliente")
    @ApiResponse(responseCode = "200", description = "Cliente encontrado")
    @ApiResponse(responseCode = "403", description = "El cliente no es el dueño (o el id no existe)")
    @ApiResponse(responseCode = "404", description = "No existe un cliente con ese id (solo ADMIN)")
    public Mono<ResponseEntity<CustomerResponse>> findById(@PathVariable String id, Authentication authentication) {
        return accessPolicy.checkAccess(id, authentication)
                .then(Mono.defer(() -> customerService.findById(id)))
                .map(CustomerController::withETag);
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

    @PatchMapping("/{id}")
    @Operation(summary = "Actualizar email y/o teléfono de un cliente")
    @ApiResponse(responseCode = "200", description = "Contacto actualizado")
    @ApiResponse(responseCode = "400", description = "Datos inválidos")
    @ApiResponse(responseCode = "403", description = "El cliente no es el dueño (o el id no existe)")
    @ApiResponse(responseCode = "404", description = "No existe un cliente con ese id (solo ADMIN)")
    @ApiResponse(responseCode = "409", description = "El cliente fue modificado por otra operación")
    @ApiResponse(responseCode = "412", description = "If-Match no coincide con la versión actual")
    public Mono<ResponseEntity<CustomerResponse>> updateContact(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody UpdateContactRequest request,
            Authentication authentication) {
        // Primero el acceso: si no es el dueño, no se toca Cosmos
        return accessPolicy.checkAccess(id, authentication)
                .then(Mono.defer(() -> customerService.updateContact(id, request.email(), request.phone(), ifMatch)))
                .map(CustomerController::withETag);
    }

    @PutMapping("/{id}/identity")
    @Operation(summary = "Vincular un cliente con su cuenta de acceso (solo ADMIN)")
    @ApiResponse(responseCode = "200", description = "Cuenta vinculada (o ya lo estaba)")
    @ApiResponse(responseCode = "400", description = "identityId no es un UUID")
    @ApiResponse(responseCode = "404", description = "No existe un cliente con ese id")
    @ApiResponse(responseCode = "409", description = "La cuenta está en otro cliente, el cliente tiene otra cuenta o hubo una modificación concurrente")
    @ApiResponse(responseCode = "412", description = "If-Match no coincide con la versión actual")
    public Mono<ResponseEntity<CustomerResponse>> linkIdentity(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody LinkIdentityRequest request) {
        return customerService.linkIdentity(id, request.identityId(), ifMatch)
                .map(CustomerController::withETag);
    }

    @DeleteMapping("/{id}/identity")
    @Operation(summary = "Desvincular un cliente de su cuenta de acceso (solo ADMIN)")
    @ApiResponse(responseCode = "204", description = "Cuenta desvinculada (o no tenía)")
    @ApiResponse(responseCode = "404", description = "No existe un cliente con ese id")
    @ApiResponse(responseCode = "409", description = "El cliente fue modificado por otra operación")
    @ApiResponse(responseCode = "412", description = "If-Match no coincide con la versión actual")
    public Mono<ResponseEntity<Void>> unlinkIdentity(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return customerService.unlinkIdentity(id, ifMatch)
                .thenReturn(ResponseEntity.noContent().build());
    }

    // La versión del documento (_etag de Cosmos) viaja en el header ETag
    private static ResponseEntity<CustomerResponse> withETag(Customer customer) {
        return ResponseEntity.ok()
                .eTag(customer.getEtag())
                .body(CustomerResponse.from(customer));
    }
}