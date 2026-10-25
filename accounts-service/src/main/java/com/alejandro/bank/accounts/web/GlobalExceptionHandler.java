package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.exception.CustomerNotEligibleException;
import com.alejandro.bank.accounts.exception.CustomersUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
