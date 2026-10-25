package com.alejandro.bank.customers.web;

import com.alejandro.bank.customers.exception.CustomerAlreadyExistsException;
import com.alejandro.bank.customers.exception.CustomerModifiedException;
import com.alejandro.bank.customers.exception.CustomerNotFoundException;
import com.alejandro.bank.customers.exception.CustomerPreconditionFailedException;
import com.alejandro.bank.customers.exception.IdentityAlreadyLinkedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 400: un campo con @Valid no cumple sus reglas (@NotBlank, @Email, @Pattern...)
    @ExceptionHandler(WebExchangeBindException.class)
    public ProblemDetail handleValidation(WebExchangeBindException ex) {
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

    // 400: el cuerpo no es JSON válido o le falta algo obligatorio
    @ExceptionHandler(ServerWebInputException.class)
    public ProblemDetail handleBadInput(ServerWebInputException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "El cuerpo de la solicitud no es válido");
        problem.setTitle("Solicitud mal formada");
        return problem;
    }

    // 404
    @ExceptionHandler(CustomerNotFoundException.class)
    public ProblemDetail handleNotFound(CustomerNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Recurso no encontrado");
        return problem;
    }

    // 409
    @ExceptionHandler(CustomerAlreadyExistsException.class)
    public ProblemDetail handleConflict(CustomerAlreadyExistsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Conflicto");
        return problem;
    }

    // 400: un @RequestParam no cumple sus reglas (@Pattern, @Min, @Max...)
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

    // 409: otro cambio se guardó entre nuestra lectura y nuestro guardado (_etag de Cosmos)
    @ExceptionHandler(CustomerModifiedException.class)
    public ProblemDetail handleModified(CustomerModifiedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Conflicto de concurrencia");
        return problem;
    }

    // 412: el If-Match enviado no coincide con la versión actual
    @ExceptionHandler(CustomerPreconditionFailedException.class)
    public ProblemDetail handlePreconditionFailed(CustomerPreconditionFailedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED, ex.getMessage());
        problem.setTitle("Versión desactualizada");
        return problem;
    }

    // 409: la cuenta ya está en otro cliente, o el cliente ya tiene otra cuenta (BANK-48)
    @ExceptionHandler(IdentityAlreadyLinkedException.class)
    public ProblemDetail handleIdentityAlreadyLinked(IdentityAlreadyLinkedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Vínculo en conflicto");
        return problem;
    }
}
