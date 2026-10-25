package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.exception.AccountNotActiveException;
import com.alejandro.bank.accounts.exception.AccountNotFoundException;
import com.alejandro.bank.accounts.exception.CustomerNotEligibleException;
import com.alejandro.bank.accounts.exception.CustomersUnavailableException;
import com.alejandro.bank.accounts.exception.IdempotencyKeyConflictException;
import com.alejandro.bank.accounts.exception.InsufficientFundsException;
import com.alejandro.bank.accounts.exception.SameAccountException;
import com.alejandro.bank.accounts.exception.TransferFailedException;
import com.alejandro.bank.accounts.exception.TransferNotFoundException;
import com.alejandro.bank.accounts.exception.TransferRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;

// Errores en ProblemDetail, con el mismo formato que customers-service (versión Spring MVC)
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 400: un campo con @Valid no cumple sus reglas (@NotBlank, @NotNull, @Pattern...)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "La solicitud tiene campos inválidos");
        problem.setTitle("Datos inválidos");

        List<Map<String, String>> errors = ex.getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    // 400: el cuerpo no es JSON válido o trae un valor que no existe (por ejemplo currency "EUR")
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleBadInput(HttpMessageNotReadableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "El cuerpo de la solicitud no es válido");
        problem.setTitle("Solicitud mal formada");
        return problem;
    }

    // 400: un @RequestParam no cumple sus reglas (@Pattern...)
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleParameterValidation(HandlerMethodValidationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "La solicitud tiene parámetros inválidos");
        problem.setTitle("Datos inválidos");

        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> Map.of(
                                "field", String.valueOf(result.getMethodParameter().getParameterName()),
                                "message", String.valueOf(error.getDefaultMessage()))))
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    // 400: falta un parámetro o header obligatorio, o no tiene el tipo esperado (un {id} o una
    // Idempotency-Key que no es UUID)
    @ExceptionHandler({MissingServletRequestParameterException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    public ProblemDetail handleBadParameter(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Falta un parámetro o tiene un formato inválido");
        problem.setTitle("Datos inválidos");
        return problem;
    }

    // 404: la cuenta no existe (solo lo recibe el asesor)
    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleAccountNotFound(AccountNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Recurso no encontrado");
        return problem;
    }

    // 404: la transferencia no existe (solo lo recibe el asesor, BANK-78)
    @ExceptionHandler(TransferNotFoundException.class)
    public ProblemDetail handleTransferNotFound(TransferNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Recurso no encontrado");
        return problem;
    }

    // 422: el core rechazó la transferencia y el dinero volvió al origen (BANK-78). Lleva el id y el estado: el
    // cliente puede consultarla después con GET /transfers/{id}
    @ExceptionHandler(TransferFailedException.class)
    public ProblemDetail handleTransferFailed(TransferFailedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("Transferencia rechazada por el core bancario");
        problem.setProperty("transferId", ex.getTransfer().getId());
        // "transferStatus" y no "status": ese campo es del ProblemDetail (el código HTTP, RFC 9457)
        problem.setProperty("transferStatus", ex.getTransfer().getStatus());
        problem.setProperty("reason", ex.getTransfer().getFailureReason());
        return problem;
    }

    // 422: la cuenta está bloqueada o cerrada (BANK-55)
    @ExceptionHandler(AccountNotActiveException.class)
    public ProblemDetail handleAccountNotActive(AccountNotActiveException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("Cuenta no activa");
        return problem;
    }

    // 400: transferir a la misma cuenta (BANK-56)
    @ExceptionHandler(SameAccountException.class)
    public ProblemDetail handleSameAccount(SameAccountException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Datos inválidos");
        return problem;
    }

    // 409: la Idempotency-Key ya se usó con otros datos (BANK-56)
    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyKeyConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Idempotency-Key en conflicto");
        return problem;
    }

    // 422: fondos insuficientes (BANK-56)
    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail handleInsufficientFunds(InsufficientFundsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("Fondos insuficientes");
        return problem;
    }

    // 422: destino u origen inexistente o monedas distintas (BANK-56)
    @ExceptionHandler(TransferRejectedException.class)
    public ProblemDetail handleTransferRejected(TransferRejectedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("Transferencia rechazada");
        return problem;
    }

    // 422: el cliente no existe o está bloqueado
    @ExceptionHandler(CustomerNotEligibleException.class)
    public ProblemDetail handleCustomerNotEligible(CustomerNotEligibleException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setTitle("Cliente no válido");
        return problem;
    }

    // 503: customers-service no disponible; no se escribió nada
    @ExceptionHandler(CustomersUnavailableException.class)
    public ProblemDetail handleCustomersUnavailable(CustomersUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("Servicio no disponible");
        return problem;
    }
}
