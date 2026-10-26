package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.customers.TokenRelay;
import com.alejandro.bank.accounts.domain.Account;
import com.alejandro.bank.contracts.customers.v1.CustomersServiceGrpc;
import com.alejandro.bank.contracts.customers.v1.GetMyCustomerRequest;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.grpc.RegisterClientInterceptor;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.concurrent.TimeUnit;

// Primer endpoint (BANK-99): prueba de punta a punta del esqueleto (token, gRPC a customers, base). Las demás
// consultas, la apertura y los depósitos llegan en BANK-100, con el mapeo de errores de customers
@Path("/accounts")
@Produces(MediaType.APPLICATION_JSON)
public class AccountResource {

    @GrpcClient("customers")
    @RegisterClientInterceptor(TokenRelay.class)
    CustomersServiceGrpc.CustomersServiceBlockingStub customers;

    @GET
    @Path("/me")
    @RolesAllowed("CUSTOMER")
    public List<AccountResponse> me() {
        String customerId = customers.withDeadlineAfter(2, TimeUnit.SECONDS)
                .getMyCustomer(GetMyCustomerRequest.getDefaultInstance()).getId();
        return Account.ofCustomer(customerId).stream().map(AccountResponse::from).toList();
    }
}
