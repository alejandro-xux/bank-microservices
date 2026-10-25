package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.domain.Transfer;
import com.alejandro.bank.accounts.domain.TransferStatus;
import com.alejandro.bank.accounts.exception.TransferFailedException;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    @ApiResponse(responseCode = "201", description = "Transferencia completada: el core registró el asiento y el destino "
            + "recibió el dinero. En un reintento con la misma clave, la misma transferencia con Idempotent-Replayed: true")
    @ApiResponse(responseCode = "202", description = "Pendiente: el débito está hecho y el core todavía no confirmó; "
            + "se resuelve sola (consultar con GET /transfers/{id})")
    @ApiResponse(responseCode = "400", description = "Datos inválidos, misma cuenta o falta la Idempotency-Key")
    @ApiResponse(responseCode = "403", description = "La cuenta origen no es del cliente (o no existe)")
    @ApiResponse(responseCode = "409", description = "La Idempotency-Key ya se usó con otros datos")
    @ApiResponse(responseCode = "422", description = "Fondos insuficientes, monedas distintas, cuenta no disponible, o el core "
            + "rechazó la transferencia (el dinero volvió al origen; el detalle trae transferId, transferStatus y reason)")
    @ApiResponse(responseCode = "503", description = "No se pudo consultar el cliente")
    public ResponseEntity<TransferResponse> transfer(
            @Parameter(description = "UUID nuevo por cada transferencia; el mismo en cada reintento")
            @RequestHeader(IdempotencyHeaders.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody TransferRequest request,
            Authentication authentication) {
        TransferService.Result result = transferService.transfer(idempotencyKey, request.fromAccountId(),
                request.toAccountNumber(), request.amount(), authentication);
        // El estado de la SAGA (BANK-78) decide la respuesta; un reintento devuelve el estado de AHORA
        Transfer transfer = result.transfer();
        if (transfer.getStatus() == TransferStatus.FAILED) {
            throw new TransferFailedException(transfer);
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(
                transfer.getStatus() == TransferStatus.PENDING ? HttpStatus.ACCEPTED : HttpStatus.CREATED);
        if (result.replayed()) {
            response.header(IdempotencyHeaders.IDEMPOTENT_REPLAYED, "true");
        }
        return response.body(TransferResponse.from(transfer));
    }

    @GetMapping("/{id}")
    @Operation(summary = "En qué quedó una transferencia (PENDING, COMPLETED o FAILED)")
    @ApiResponse(responseCode = "200", description = "La transferencia con su estado")
    @ApiResponse(responseCode = "403", description = "No es de una cuenta del cliente (o no existe)")
    @ApiResponse(responseCode = "404", description = "No existe (solo el asesor)")
    public TransferResponse get(@PathVariable UUID id, Authentication authentication) {
        return TransferResponse.from(transferService.find(id, authentication));
    }
}
