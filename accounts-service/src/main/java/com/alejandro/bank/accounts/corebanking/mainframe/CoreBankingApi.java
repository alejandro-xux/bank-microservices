package com.alejandro.bank.accounts.corebanking.mainframe;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

// El core por HTTP (BANK-77, D6: interfaz HTTP de Spring sobre RestClient, sin OpenFeign). Las tramas viajan como
// texto plano; en un banco real este transporte sería IBM MQ o z/OS Connect, y solo cambiaría esta clase
@HttpExchange(contentType = "text/plain", accept = "text/plain")
interface CoreBankingApi {

    @PostExchange("/core/asientos")
    String post(@RequestBody String frame);

    @PostExchange("/core/consultas")
    String query(@RequestBody String frame);
}
