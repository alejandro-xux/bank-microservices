package com.alejandro.bank.notifications.customers;

import com.alejandro.bank.contracts.customers.v1.CustomerContact;
import com.alejandro.bank.contracts.customers.v1.CustomersServiceGrpc;
import com.alejandro.bank.contracts.customers.v1.GetContactRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

// Nombre y correo de un cliente para avisarle algo (GetContact, BANK-71). Los datos personales no viajan en el
// evento: se piden al dueño del dato en el momento de avisar
@Slf4j
@Component
public class CustomerContacts {

    public record Contact(String customerId, String name, String email) {
    }

    private final CustomersServiceGrpc.CustomersServiceBlockingStub customers;
    private final Duration deadline;

    CustomerContacts(CustomersServiceGrpc.CustomersServiceBlockingStub customers,
                     @Value("${bank.customers.deadline:2s}") Duration deadline) {
        this.customers = customers;
        this.deadline = deadline;
    }

    // Vacío si el cliente no existe: no hay a quién avisar, reintentar no lo arregla.
    // ContactsUnavailableException si customers-service o Keycloak no respondieron: se reintenta
    public Optional<Contact> find(String customerId) {
        try {
            CustomerContact contact = customers.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                    .getContact(GetContactRequest.newBuilder().setId(customerId).build());
            return Optional.of(new Contact(contact.getId(), contact.getName(), contact.getEmail()));
        } catch (StatusRuntimeException ex) {
            if (ex.getStatus().getCode() == Status.Code.NOT_FOUND) {
                log.warn("El cliente {} no existe en customers-service: no se le avisa", customerId);
                return Optional.empty();
            }
            // UNAVAILABLE o DEADLINE_EXCEEDED son lo normal; PERMISSION_DENIED o UNAUTHENTICATED serían un error
            // de configuración (rol o audiencia del token), y también se reintentan mientras se corrige
            throw new ContactsUnavailableException("customers-service respondió " + ex.getStatus().getCode(), ex);
        } catch (RuntimeException ex) {
            // El token no se pudo obtener (Keycloak caído): ni siquiera salió la llamada
            throw new ContactsUnavailableException("No se pudo obtener el token de servicio", ex);
        }
    }
}
