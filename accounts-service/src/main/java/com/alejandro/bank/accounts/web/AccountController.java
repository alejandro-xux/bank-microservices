package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.service.AccountService;
import com.alejandro.bank.accounts.service.DepositService;
import com.alejandro.bank.accounts.service.MovementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final DepositService depositService;
    private final MovementService movementService;

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

    @PostMapping("/{id}/deposits")
    @Operation(summary = "Registrar un depósito en ventanilla (solo asesor), en la moneda de la cuenta")
    @ApiResponse(responseCode = "201", description = "Movimiento CREDIT registrado; balanceAfter es el saldo nuevo. "
            + "En un reintento con la misma clave, el mismo movimiento con el header Idempotent-Replayed: true")
    @ApiResponse(responseCode = "400", description = "Monto ausente, no positivo o con más de 2 decimales, o falta la Idempotency-Key")
    @ApiResponse(responseCode = "404", description = "La cuenta no existe")
    @ApiResponse(responseCode = "409", description = "La Idempotency-Key ya se usó con otros datos")
    @ApiResponse(responseCode = "422", description = "La cuenta no está activa")
    public ResponseEntity<MovementResponse> deposit(
            @PathVariable UUID id,
            @Parameter(description = "UUID nuevo por cada depósito; el mismo en cada reintento")
            @RequestHeader(IdempotencyHeaders.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        DepositService.Result result = depositService.deposit(idempotencyKey, id, request.amount());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            response.header(IdempotencyHeaders.IDEMPOTENT_REPLAYED, "true");
        }
        return response.body(MovementResponse.from(result.movement()));
    }

    @GetMapping("/{id}/movements")
    @Operation(summary = "Historial de movimientos, del más reciente al más antiguo (el asesor cualquiera; el cliente solo las suyas)")
    @ApiResponse(responseCode = "200", description = "Una página de movimientos; en una transferencia, la otra cuenta enmascarada")
    @ApiResponse(responseCode = "400", description = "page < 0, o size fuera de 1..50")
    @ApiResponse(responseCode = "403", description = "La cuenta no es del cliente (o no existe)")
    @ApiResponse(responseCode = "404", description = "La cuenta no existe (solo asesor)")
    @ApiResponse(responseCode = "503", description = "No se pudo consultar el cliente")
    public MovementPageResponse movements(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "debe ser 0 o mayor") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "debe ser al menos 1")
            @Max(value = 50, message = "debe ser como máximo 50") int size,
            Authentication authentication) {
        return MovementPageResponse.from(movementService.history(id, page, size, authentication));
    }
}
