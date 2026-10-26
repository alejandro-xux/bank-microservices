package com.alejandro.bank.accounts.customers;

import com.alejandro.bank.accounts.exception.CustomerNotEligibleException;
import com.alejandro.bank.accounts.exception.CustomersUnavailableException;
import com.alejandro.bank.contracts.customers.v1.CustomersServiceGrpc;
import com.alejandro.bank.contracts.customers.v1.GetCustomerRequest;
import com.alejandro.bank.contracts.customers.v1.GetMyCustomerRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.grpc.RegisterClientInterceptor;
import io.quarkus.security.ForbiddenException;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

// Único lugar que sabe hablar con customers-service (como en accounts-service): el stub gRPC de contracts, con el
// token del usuario (TokenRelay) y un plazo por llamada. Traduce los Status a errores de este servicio: NOT_FOUND al
// abrir → 422; PERMISSION_DENIED en /me → el mismo 403; cualquier otra cosa (caído, plazo vencido) → 503
@ApplicationScoped
public class CustomerDirectory {

    private static final Logger LOG = Logger.getLogger(CustomerDirectory.class);

    @GrpcClient("customers")
    @RegisterClientInterceptor(TokenRelay.class)
    CustomersServiceGrpc.CustomersServiceBlockingStub customers;

    @ConfigProperty(name = "bank.customers.deadline", defaultValue = "2s")
    Duration deadline;

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
                throw new ForbiddenException("Sin cliente vinculado");
            }
            throw unavailable(ex);
        }
    }

    private CustomersServiceGrpc.CustomersServiceBlockingStub withDeadline() {
        return customers.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static CustomersUnavailableException unavailable(StatusRuntimeException ex) {
        LOG.warnf("customers-service no disponible: %s", ex.getStatus());
        return new CustomersUnavailableException(ex);
    }
}
