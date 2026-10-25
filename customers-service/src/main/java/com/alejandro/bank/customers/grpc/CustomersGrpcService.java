package com.alejandro.bank.customers.grpc;

import com.alejandro.bank.contracts.customers.v1.CustomerContact;
import com.alejandro.bank.contracts.customers.v1.CustomerStatus;
import com.alejandro.bank.contracts.customers.v1.CustomerSummary;
import com.alejandro.bank.contracts.customers.v1.CustomersServiceGrpc;
import com.alejandro.bank.contracts.customers.v1.GetContactRequest;
import com.alejandro.bank.contracts.customers.v1.GetCustomerRequest;
import com.alejandro.bank.contracts.customers.v1.GetMyCustomerRequest;
import com.alejandro.bank.customers.domain.Customer;
import com.alejandro.bank.customers.domain.Email;
import com.alejandro.bank.customers.exception.CustomerNotFoundException;
import com.alejandro.bank.customers.security.CustomerAccessPolicy;
import com.alejandro.bank.customers.service.CustomerService;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

// Servidor gRPC del contrato contracts/customers.proto (BANK-61). Las reglas de rol ya las aplicó
// GrpcSecurityConfig; aquí se reutilizan las mismas lecturas y la misma regla del dueño que REST,
// sin duplicar lógica del dominio
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomersGrpcService extends CustomersServiceGrpc.CustomersServiceImplBase {

    private final CustomerService customerService;
    private final CustomerAccessPolicy accessPolicy;

    // Solo el asesor: lee por id con el cache de Redis (como GET /customers/{id})
    @Override
    public void getCustomer(GetCustomerRequest request, StreamObserver<CustomerSummary> response) {
        reply(customerService.findById(request.getId()), CustomersGrpcService::toSummary, response);
    }

    // Solo la cuenta de servicio de notifications-service (BANK-71): nombre y correo principal para un aviso.
    // Lee con el mismo cache que GetCustomer
    @Override
    public void getContact(GetContactRequest request, StreamObserver<CustomerContact> response) {
        reply(customerService.findById(request.getId()), CustomersGrpcService::toContact, response);
    }

    // Solo un CUSTOMER: el cliente vinculado al oid del token (como GET /customers/me)
    @Override
    public void getMyCustomer(GetMyCustomerRequest request, StreamObserver<CustomerSummary> response) {
        // El SecurityContext vive en ESTE hilo de gRPC: se lee antes de pasar al mundo reactivo
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        reply(accessPolicy.ownCustomer(authentication), CustomersGrpcService::toSummary, response);
    }

    // Puente Mono → StreamObserver. Si el cliente se va (deadline vencido o cancelación), se cancela el
    // Mono: el servidor deja de trabajar en una respuesta que nadie recibirá (comprobado en BANK-59)
    private static <T> void reply(Mono<Customer> customer, Function<Customer, T> toMessage, StreamObserver<T> response) {
        Disposable subscription = customer.subscribe(
                found -> {
                    response.onNext(toMessage.apply(found));
                    response.onCompleted();
                },
                error -> response.onError(toStatus(error).asRuntimeException()));
        if (response instanceof ServerCallStreamObserver<T> call) {
            call.setOnCancelHandler(subscription::dispose);
        }
    }

    private static CustomerSummary toSummary(Customer customer) {
        return CustomerSummary.newBuilder()
                .setId(customer.getId())
                .setStatus(switch (customer.getStatus()) {
                    case ACTIVE -> CustomerStatus.ACTIVE;
                    case BLOCKED -> CustomerStatus.BLOCKED;
                })
                .build();
    }

    // Nombre para el saludo y correo principal (el primero si ninguno está marcado; vacío si no tiene)
    private static CustomerContact toContact(Customer customer) {
        List<Email> emails = customer.getEmails() == null ? List.of() : customer.getEmails();
        String email = emails.stream()
                .min(Comparator.comparing(e -> !e.primary()))
                .map(Email::address)
                .orElse("");
        return CustomerContact.newBuilder()
                .setId(customer.getId())
                .setName(customer.getFirstName() + " " + customer.getPaternalLastName())
                .setEmail(email)
                .build();
    }

    // Mensajes genéricos: el detalle (Cosmos, Redis…) queda en el log, nunca en la respuesta
    private static Status toStatus(Throwable error) {
        if (error instanceof CustomerNotFoundException) {
            return Status.NOT_FOUND.withDescription("El cliente no existe");
        }
        if (error instanceof AccessDeniedException) {
            return Status.PERMISSION_DENIED.withDescription("Acceso denegado");
        }
        log.error("Error inesperado en una consulta gRPC de cliente", error);
        return Status.INTERNAL.withDescription("Error interno");
    }
}
