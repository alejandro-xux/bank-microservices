package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.security.AccountAccessPolicy;
import com.alejandro.bank.accounts.service.AccountService;
import com.alejandro.bank.accounts.service.DepositService;
import com.alejandro.bank.accounts.service.MovementService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

// Los mismos endpoints, roles, códigos y JSON que AccountController de accounts-service (Spring). Los roles van en
// cada método (en Spring estaban en SecurityConfig): ADMIN abre, deposita y busca por cliente; CUSTOMER ve lo suyo
@Path("/accounts")
@Produces(MediaType.APPLICATION_JSON)
public class AccountResource {

    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Inject
    AccountService accountService;

    @Inject
    DepositService depositService;

    @Inject
    MovementService movementService;

    // Abrir una cuenta para un cliente (solo asesor): 201 con Location relativo, como Spring (Response.created lo
    // haría absoluto)
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccountAccessPolicy.ADMIN)
    public Response open(@Valid OpenAccountRequest request) {
        AccountResponse body = AccountResponse.from(
                accountService.open(request.customerId(), request.type(), request.currency()));
        return Response.status(Response.Status.CREATED).header("Location", "/accounts/" + body.id()).entity(body).build();
    }

    // Mis cuentas y sus saldos (el cliente vinculado al token)
    @GET
    @Path("/me")
    @RolesAllowed(AccountAccessPolicy.CUSTOMER)
    public List<AccountResponse> findMine() {
        return accountService.myAccounts().stream().map(AccountResponse::from).toList();
    }

    // Una cuenta: el asesor cualquiera (o 404); el cliente solo las suyas (ajena o inexistente → el mismo 403)
    @GET
    @Path("/{id}")
    @RolesAllowed({AccountAccessPolicy.ADMIN, AccountAccessPolicy.CUSTOMER})
    public AccountResponse findById(@PathParam("id") String id) {
        return AccountResponse.from(accountService.findById(uuid(id, "id")));
    }

    // Cuentas de un cliente (solo asesor), sin llamar a customers-service
    @GET
    @RolesAllowed(AccountAccessPolicy.ADMIN)
    public List<AccountResponse> findByCustomer(@QueryParam("customerId") String customerId) {
        if (customerId == null) {
            throw new BadParameterException("customerId");
        }
        if (!UUID_FORMAT.matcher(customerId).matches()) {
            throw new InvalidParametersException("customerId", "debe ser un UUID");
        }
        return accountService.findByCustomer(customerId).stream().map(AccountResponse::from).toList();
    }

    // Depósito en ventanilla (solo asesor), idempotente: un reintento con la misma clave devuelve el mismo movimiento
    // con Idempotent-Replayed: true
    @POST
    @Path("/{id}/deposits")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccountAccessPolicy.ADMIN)
    public Response deposit(@PathParam("id") String id,
                            @HeaderParam(IdempotencyHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
                            @Valid DepositRequest request) {
        UUID accountId = uuid(id, "id");
        DepositService.Result result = depositService.deposit(
                uuid(idempotencyKey, IdempotencyHeaders.IDEMPOTENCY_KEY), accountId, request.amount());
        Response.ResponseBuilder response = Response.status(Response.Status.CREATED)
                .entity(MovementResponse.from(result.movement()));
        if (result.replayed()) {
            response.header(IdempotencyHeaders.IDEMPOTENT_REPLAYED, "true");
        }
        return response.build();
    }

    // Historial, del más reciente al más antiguo: page >= 0 y size entre 1 y 50
    @GET
    @Path("/{id}/movements")
    @RolesAllowed({AccountAccessPolicy.ADMIN, AccountAccessPolicy.CUSTOMER})
    public MovementPageResponse movements(@PathParam("id") String id,
                                          @QueryParam("page") @DefaultValue("0") String page,
                                          @QueryParam("size") @DefaultValue("20") String size) {
        UUID accountId = uuid(id, "id");
        int pageNumber = integer(page, "page");
        int pageSize = integer(size, "size");
        if (pageNumber < 0) {
            throw new InvalidParametersException("page", "debe ser 0 o mayor");
        }
        if (pageSize < 1) {
            throw new InvalidParametersException("size", "debe ser al menos 1");
        }
        if (pageSize > 50) {
            throw new InvalidParametersException("size", "debe ser como máximo 50");
        }
        return MovementPageResponse.from(movementService.history(accountId, pageNumber, pageSize));
    }

    private static UUID uuid(String value, String parameter) {
        if (value == null || !UUID_FORMAT.matcher(value).matches()) {
            throw new BadParameterException(parameter);
        }
        return UUID.fromString(value);
    }

    private static int integer(String value, String parameter) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new BadParameterException(parameter);
        }
    }
}
