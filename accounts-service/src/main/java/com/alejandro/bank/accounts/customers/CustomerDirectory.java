package com.alejandro.bank.accounts.customers;

import com.alejandro.bank.accounts.exception.CustomerNotEligibleException;
import com.alejandro.bank.accounts.exception.CustomersUnavailableException;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

// Único lugar que conoce los errores HTTP de customers-service: los traduce a errores de este servicio.
// Cualquier respuesta inesperada, caída o timeout → 503 (la causa queda en el log, no en la respuesta)
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerDirectory {

    private final CustomersClient customersClient;

    // Para abrir una cuenta (asesor): 404 → 422 "no existe"
    public CustomerSummary findById(String customerId) {
        try {
            return customersClient.findById(customerId);
        } catch (FeignException.NotFound ex) {
            throw CustomerNotEligibleException.notFound(customerId);
        } catch (FeignException ex) {
            throw unavailable(ex);
        }
    }

    // El cliente del token (CUSTOMER): 403 en customers = sin cliente vinculado → el mismo 403 aquí
    public CustomerSummary findMe() {
        try {
            return customersClient.findMe();
        } catch (FeignException.Forbidden ex) {
            throw new AccessDeniedException("Sin cliente vinculado");
        } catch (FeignException ex) {
            throw unavailable(ex);
        }
    }

    private static CustomersUnavailableException unavailable(FeignException ex) {
        log.warn("customers-service no disponible: {}", ex.getMessage());
        return new CustomersUnavailableException(ex);
    }
}
