package com.alejandro.bank.accounts.corebanking.mainframe;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

// El core por HTTP, como la interfaz @HttpExchange de accounts-service, pero con el REST Client de Quarkus. Las
// tramas viajan como texto plano; en un banco real este transporte sería IBM MQ o z/OS Connect
@Path("/core")
@Consumes(MediaType.TEXT_PLAIN)
@Produces(MediaType.TEXT_PLAIN)
public interface CoreBankingApi {

    @POST
    @Path("/asientos")
    String post(String frame);

    @POST
    @Path("/consultas")
    String query(String frame);
}
