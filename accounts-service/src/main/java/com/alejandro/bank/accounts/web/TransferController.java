package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
@RequiredArgsConstructor
@Tag(name = "Transfers", description = "Transferencias entre cuentas")
public class TransferController {

    private final TransferService transferService;

    @PostMapping
    @Operation(summary = "Transferir desde una cuenta propia (el asesor desde cualquiera) a otra por su número")
    @ApiResponse(responseCode = "201", description = "Transferencia hecha; en un reintento con la misma clave, "
            + "la misma transferencia con el header Idempotent-Replayed: true")
    @ApiResponse(responseCode = "400", description = "Datos inválidos, misma cuenta o falta la Idempotency-Key")
    @ApiResponse(responseCode = "403", description = "La cuenta origen no es del cliente (o no existe)")
    @ApiResponse(responseCode = "409", description = "La Idempotency-Key ya se usó con otros datos")
    @ApiResponse(responseCode = "422", description = "Fondos insuficientes, monedas distintas o cuenta no disponible")
    @ApiResponse(responseCode = "503", description = "No se pudo consultar el cliente")
    public ResponseEntity<TransferResponse> transfer(
            @Parameter(description = "UUID nuevo por cada transferencia; el mismo en cada reintento")
            @RequestHeader(IdempotencyHeaders.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody TransferRequest request,
            Authentication authentication) {
        TransferService.Result result = transferService.transfer(idempotencyKey, request.fromAccountId(),
                request.toAccountNumber(), request.amount(), authentication);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            response.header(IdempotencyHeaders.IDEMPOTENT_REPLAYED, "true");
        }
        return response.body(TransferResponse.from(result.transfer()));
    }
}
