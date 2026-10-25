package com.alejandro.bank.accounts.customers;

import com.alejandro.bank.contracts.customers.v1.CustomersServiceGrpc;
import com.alejandro.bank.contracts.customers.v1.GetCustomerRequest;
import com.alejandro.bank.contracts.customers.v1.GetMyCustomerRequest;
import com.alejandro.bank.accounts.exception.CustomerNotEligibleException;
import com.alejandro.bank.accounts.exception.CustomersUnavailableException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

// Único lugar que conoce cómo se habla con customers-service (por gRPC desde BANK-62; antes Feign).
// Traduce sus Status a errores de este servicio, y el resto del código no se entera del protocolo.
// Cualquier respuesta inesperada, caída o deadline vencido → 503 (la causa queda en el log)
@Slf4j
@Component
public class CustomerDirectory {

    private final CustomersServiceGrpc.CustomersServiceBlockingStub customers;
    private final Duration deadline;

    CustomerDirectory(CustomersServiceGrpc.CustomersServiceBlockingStub customers,
                      @Value("${bank.customers.deadline:2s}") Duration deadline) {
        this.customers = customers;
        this.deadline = deadline;
    }

    // Para abrir una cuenta (asesor): NOT_FOUND → 422 "no existe"
    public CustomerSummary findById(String customerId) {
        try {
            return CustomerSummary.from(withDeadline()
                    .getCustomer(GetCustomerRequest.newBuilder().setId(customerId).build()));
        } catch (StatusRuntimeException ex) {
            if (ex.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw CustomerNotEligibleException.notFound(customerId);
            }
            throw unavailable(ex);
        }
    }

    // El cliente del token (CUSTOMER): PERMISSION_DENIED = sin cliente vinculado → el mismo 403 aquí
    public CustomerSummary findMe() {
        try {
            return CustomerSummary.from(withDeadline().getMyCustomer(GetMyCustomerRequest.getDefaultInstance()));
        } catch (StatusRuntimeException ex) {
            if (ex.getStatus().getCode() == Status.Code.PERMISSION_DENIED) {
                throw new AccessDeniedException("Sin cliente vinculado");
            }
            throw unavailable(ex);
        }
    }

    // El plazo corre por llamada: el stub base no lo trae, cada consulta arma el suyo
    private CustomersServiceGrpc.CustomersServiceBlockingStub withDeadline() {
        return customers.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static CustomersUnavailableException unavailable(StatusRuntimeException ex) {
        log.warn("customers-service no disponible: {}", ex.getStatus());
        return new CustomersUnavailableException(ex);
    }
}
