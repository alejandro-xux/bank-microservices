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
import com.alejandro.bank.accounts.security.Problems;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import io.quarkus.hibernate.validator.runtime.jaxrs.ResteasyReactiveViolationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.List;
import java.util.Map;

// Errores en ProblemDetail con los mismos títulos, textos y códigos que GlobalExceptionHandler de accounts-service
public class ErrorMappers {

    // 400: un campo del cuerpo no cumple sus reglas (@NotBlank, @Pattern, @DecimalMin...)
    @ServerExceptionMapper
    public Response invalidBody(ResteasyReactiveViolationException ex, UriInfo uri) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(violation -> Map.of("field", field(violation), "message", violation.getMessage()))
                .toList();
        return withErrors(Problems.of(400, "Datos inválidos", "La solicitud tiene campos inválidos", uri), errors);
    }

    // 400: un parámetro de la URL no cumple su regla
    @ServerExceptionMapper
    public Response invalidParameters(InvalidParametersException ex, UriInfo uri) {
        return withErrors(Problems.of(400, "Datos inválidos", "La solicitud tiene parámetros inválidos", uri),
                ex.getErrors());
    }

    // 400: falta un parámetro o no tiene el formato esperado
    @ServerExceptionMapper
    public Response badParameter(BadParameterException ex, UriInfo uri) {
        return Problems.of(400, "Datos inválidos", "Falta un parámetro o tiene un formato inválido", uri).build();
    }

    // 400: el cuerpo no es JSON válido o trae un valor que no existe (por ejemplo currency "EUR"). Quarkus trae su
    // propio mapper para MismatchedInputException (más específico que JsonProcessingException): se nombran los dos
    @ServerExceptionMapper({JsonProcessingException.class, MismatchedInputException.class})
    public Response badJson(UriInfo uri) {
        return Problems.of(400, "Solicitud mal formada", "El cuerpo de la solicitud no es válido", uri).build();
    }

    @ServerExceptionMapper
    public Response accountNotFound(AccountNotFoundException ex, UriInfo uri) {
        return Problems.of(404, "Recurso no encontrado", ex.getMessage(), uri).build();
    }

    @ServerExceptionMapper
    public Response accountNotActive(AccountNotActiveException ex, UriInfo uri) {
        return Problems.of(422, "Cuenta no activa", ex.getMessage(), uri).build();
    }

    @ServerExceptionMapper
    public Response idempotencyConflict(IdempotencyKeyConflictException ex, UriInfo uri) {
        return Problems.of(409, "Idempotency-Key en conflicto", ex.getMessage(), uri).build();
    }

    @ServerExceptionMapper
    public Response insufficientFunds(InsufficientFundsException ex, UriInfo uri) {
        return Problems.of(422, "Fondos insuficientes", ex.getMessage(), uri).build();
    }

    @ServerExceptionMapper
    public Response customerNotEligible(CustomerNotEligibleException ex, UriInfo uri) {
        return Problems.of(422, "Cliente no válido", ex.getMessage(), uri).build();
    }

    @ServerExceptionMapper
    public Response customersUnavailable(CustomersUnavailableException ex, UriInfo uri) {
        return Problems.of(503, "Servicio no disponible", ex.getMessage(), uri).build();
    }

    @ServerExceptionMapper
    public Response transferNotFound(TransferNotFoundException ex, UriInfo uri) {
        return Problems.of(404, "Recurso no encontrado", ex.getMessage(), uri).build();
    }

    // 422: el core rechazó la transferencia y el dinero volvió al origen. Lleva el id, el estado ("transferStatus":
    // "status" es el código HTTP del ProblemDetail) y el motivo, para consultarla después con GET /transfers/{id}
    @ServerExceptionMapper
    @SuppressWarnings("unchecked")
    public Response transferFailed(TransferFailedException ex, UriInfo uri) {
        Response response = Problems.of(422, "Transferencia rechazada por el core bancario", ex.getMessage(), uri).build();
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        body.put("transferId", ex.getTransfer().getId());
        body.put("transferStatus", ex.getTransfer().getStatus());
        body.put("reason", ex.getTransfer().getFailureReason());
        return response;
    }

    @ServerExceptionMapper
    public Response transferRejected(TransferRejectedException ex, UriInfo uri) {
        return Problems.of(422, "Transferencia rechazada", ex.getMessage(), uri).build();
    }

    @ServerExceptionMapper
    public Response sameAccount(SameAccountException ex, UriInfo uri) {
        return Problems.of(400, "Datos inválidos", ex.getMessage(), uri).build();
    }

    // "open.request.customerId" → "customerId"
    private static String field(ConstraintViolation<?> violation) {
        String last = null;
        for (Path.Node node : violation.getPropertyPath()) {
            last = node.getName();
        }
        return last;
    }

    @SuppressWarnings("unchecked")
    private static Response withErrors(Response.ResponseBuilder builder, List<Map<String, String>> errors) {
        Response response = builder.build();
        ((Map<String, Object>) response.getEntity()).put("errors", errors);
        return response;
    }
}
