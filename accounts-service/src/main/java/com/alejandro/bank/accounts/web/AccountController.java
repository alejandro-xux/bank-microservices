package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.service.AccountService;
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

import java.net.URI;

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
}
