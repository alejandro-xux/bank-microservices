package com.alejandro.bank.notifications;

import com.jayway.jsonpath.JsonPath;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Lo que llegó a Mailpit, por su API (http://localhost:8025/api/v1)
public final class MailpitInbox {

    public record Mail(String to, String subject, String text) {
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private final String api;

    MailpitInbox(String baseUrl) {
        this.api = baseUrl + "/api/v1";
    }

    public void clear() throws Exception {
        HTTP.send(HttpRequest.newBuilder(URI.create(api + "/messages")).DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }

    public List<Mail> mails() throws Exception {
        String list = get("/messages?limit=200");
        List<Mail> mails = new ArrayList<>();
        for (Map<String, Object> message : JsonPath.<List<Map<String, Object>>>read(list, "$.messages")) {
            String id = (String) message.get("ID");
            String full = get("/message/" + id);
            mails.add(new Mail(JsonPath.read(full, "$.To[0].Address"), JsonPath.read(full, "$.Subject"), JsonPath.read(full, "$.Text")));
        }
        return mails;
    }

    // Los correos de UNA transferencia (por su monto en el asunto): el topic y la bandeja son de toda la suite, y
    // el consumidor de otro contexto de Spring puede mandar tarde el correo de otra prueba.
    // Espera hasta que haya al menos "count" (o 15 s) y después un poco más, por si llegara alguno de sobra
    public List<Mail> awaitAbout(String amount, int count) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (about(amount).size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        Thread.sleep(1500);
        return about(amount);
    }

    private List<Mail> about(String amount) throws Exception {
        return mails().stream().filter(mail -> mail.subject().contains(amount)).toList();
    }

    private String get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(api + path)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }
}
