package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.domain.Transfer;
import com.alejandro.bank.accounts.domain.TransferStatus;
import com.alejandro.bank.accounts.exception.TransferFailedException;
import com.alejandro.bank.accounts.security.AccountAccessPolicy;
import com.alejandro.bank.accounts.service.TransferService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;
import java.util.regex.Pattern;

// Los mismos endpoints, códigos y JSON que TransferController de accounts-service: el estado de la SAGA decide la
// respuesta (COMPLETED → 201, PENDING → 202, FAILED → 422 con el id) y un reintento devuelve el estado de AHORA
@Path("/transfers")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({AccountAccessPolicy.ADMIN, AccountAccessPolicy.CUSTOMER})
public class TransferResource {

    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Inject
    TransferService transferService;

    // Desde una cuenta propia (el asesor desde cualquiera) a otra por su número, idempotente por Idempotency-Key
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response transfer(@HeaderParam(IdempotencyHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
                             @Valid TransferRequest request) {
        TransferService.Result result = transferService.transfer(uuid(idempotencyKey, IdempotencyHeaders.IDEMPOTENCY_KEY),
                request.fromAccountId(), request.toAccountNumber(), request.amount());
        Transfer transfer = result.transfer();
        if (transfer.getStatus() == TransferStatus.FAILED) {
            throw new TransferFailedException(transfer);
        }
        Response.ResponseBuilder response = Response.status(
                        transfer.getStatus() == TransferStatus.PENDING ? Response.Status.ACCEPTED : Response.Status.CREATED)
                .entity(TransferResponse.from(transfer));
        if (result.replayed()) {
            response.header(IdempotencyHeaders.IDEMPOTENT_REPLAYED, "true");
        }
        return response.build();
    }

    // En qué quedó una transferencia: el cliente solo las de sus cuentas (ajena o inexistente → 403)
    @GET
    @Path("/{id}")
    public TransferResponse get(@PathParam("id") String id) {
        return TransferResponse.from(transferService.find(uuid(id, "id")));
    }

    private static UUID uuid(String value, String parameter) {
        if (value == null || !UUID_FORMAT.matcher(value).matches()) {
            throw new BadParameterException(parameter);
        }
        return UUID.fromString(value);
    }
}
