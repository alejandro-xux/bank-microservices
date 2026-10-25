package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts", description = "Cuentas de ahorros y corrientes")
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @Operation(summary = "Abrir una cuenta para un cliente (solo asesor)")
    @ApiResponse(responseCode = "201", description = "Cuenta abierta con saldo 0.00")
    @ApiResponse(responseCode = "400", description = "Datos inválidos")
    @ApiResponse(responseCode = "422", description = "El cliente no existe o está bloqueado")
    @ApiResponse(responseCode = "503", description = "No se pudo consultar el cliente; la cuenta no se abrió")
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
        AccountResponse body = AccountResponse.from(
                accountService.open(request.customerId(), request.type(), request.currency()));
        return ResponseEntity.created(URI.create("/accounts/" + body.id())).body(body);
    }

    @GetMapping("/me")
    @Operation(summary = "Mis cuentas y sus saldos (el cliente vinculado al token)")
    @ApiResponse(responseCode = "200", description = "Mis cuentas, de la más antigua a la más nueva")
    @ApiResponse(responseCode = "403", description = "El token no es de un cliente o no tiene un cliente vinculado")
    @ApiResponse(responseCode = "503", description = "No se pudo consultar el cliente")
    public List<AccountResponse> findMine() {
        return accountService.myAccounts().stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar una cuenta (el asesor cualquiera; el cliente solo las suyas)")
    @ApiResponse(responseCode = "200", description = "La cuenta")
    @ApiResponse(responseCode = "403", description = "La cuenta no es del cliente (o no existe)")
    @ApiResponse(responseCode = "404", description = "La cuenta no existe (solo asesor)")
    @ApiResponse(responseCode = "503", description = "No se pudo consultar el cliente")
    public AccountResponse findById(@PathVariable UUID id, Authentication authentication) {
        return AccountResponse.from(accountService.findById(id, authentication));
    }

    @GetMapping
    @Operation(summary = "Cuentas de un cliente (solo asesor)")
    @ApiResponse(responseCode = "200", description = "Las cuentas del cliente (vacía si no tiene)")
    @ApiResponse(responseCode = "400", description = "customerId falta o no es un UUID")
    public List<AccountResponse> findByCustomer(
            @RequestParam
            @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                    message = "debe ser un UUID")
            String customerId) {
        return accountService.findByCustomer(customerId).stream().map(AccountResponse::from).toList();
    }
}
