package com.alejandro.bank.perf;

import com.alejandro.bank.contracts.customers.v1.CustomerSummary;
import com.alejandro.bank.contracts.customers.v1.CustomersServiceGrpc;
import com.alejandro.bank.contracts.customers.v1.GetCustomerRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Mide, contra el entorno local, cuánto cuesta cada tramo de la consulta de un cliente (BANK-63):
//   1. la llamada interna sola: REST GET /customers/{id} frente a gRPC GetCustomer (mismo cache de Redis)
//   2. el camino completo de Ana: GET /accounts/me (accounts → customers por gRPC, con el cache del vínculo)
// Un solo hilo, llamadas secuenciales, conexiones reutilizadas (HTTP keep-alive y un canal gRPC), sin TLS.
// No pasa por el gateway: su rate limit (10/s) cortaría la medición
public final class InternalCallBenchmark {

    private static final String KEYCLOAK = "http://localhost:8180/realms/bank/protocol/openid-connect/token";
    private static final String CUSTOMERS_REST = "http://localhost:8081";
    private static final String CUSTOMERS_GRPC_HOST = "localhost";
    private static final int CUSTOMERS_GRPC_PORT = 9091;
    private static final String ACCOUNTS_REST = "http://localhost:8082";
    // Ana, la clienta de prueba con vínculo (BANK-35)
    private static final String ANA = "c15a442d-5146-471e-8804-746f4b42253b";

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        int iterations = option(args, "--iterations", 1000);
        int warmup = option(args, "--warmup", 100);
        String admin = token("asesor", "asesor-local");
        String ana = token("ana", "ana-local");

        ManagedChannel channel = ManagedChannelBuilder.forAddress(CUSTOMERS_GRPC_HOST, CUSTOMERS_GRPC_PORT)
                .usePlaintext()
                .build();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + admin);
        CustomersServiceGrpc.CustomersServiceBlockingStub grpc = CustomersServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        GetCustomerRequest request = GetCustomerRequest.newBuilder().setId(ANA).build();

        System.out.printf(Locale.ROOT, "%n%d llamadas por caso, después de %d de calentamiento%n%n", iterations, warmup);
        System.out.printf(Locale.ROOT, "%-58s %9s %9s %10s%n", "Caso", "p50 ms", "p95 ms", "bytes");

        int restBytes = get(CUSTOMERS_REST + "/customers/" + ANA, admin).length;
        report("1a. interna REST  GET /customers/{id} (JSON completo)",
                measure(iterations, warmup, () -> get(CUSTOMERS_REST + "/customers/" + ANA, admin)), restBytes);

        CustomerSummary summary = grpc.getCustomer(request);
        report("1b. interna gRPC  GetCustomer (protobuf, id + status)",
                measure(iterations, warmup, () -> grpc.getCustomer(request)), summary.getSerializedSize());

        // Mismos dos campos que el mensaje gRPC, pero en JSON: compara la codificación, no el contenido
        String sameFieldsAsJson = "{\"id\":\"" + summary.getId() + "\",\"status\":\"" + summary.getStatus() + "\"}";
        System.out.printf(Locale.ROOT, "%-58s %9s %9s %10d%n", "    (los mismos 2 campos en JSON)", "", "",
                sameFieldsAsJson.getBytes(StandardCharsets.UTF_8).length);

        report("2.  completo      GET /accounts/me (Ana, gRPC + cache)",
                measure(iterations, warmup, () -> get(ACCOUNTS_REST + "/accounts/me", ana)),
                get(ACCOUNTS_REST + "/accounts/me", ana).length);

        channel.shutdownNow();
    }

    private static double[] measure(int iterations, int warmup, Callable<?> call) throws Exception {
        for (int i = 0; i < warmup; i++) {
            call.call();
        }
        double[] millis = new double[iterations];
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            call.call();
            millis[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        Arrays.sort(millis);
        return millis;
    }

    private static void report(String name, double[] sorted, int bytes) {
        System.out.printf(Locale.ROOT, "%-58s %9.2f %9.2f %10d%n", name,
                sorted[sorted.length / 2], sorted[(int) Math.ceil(sorted.length * 0.95) - 1], bytes);
    }

    private static byte[] get(String url, String token) throws Exception {
        HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(url + " respondió " + response.statusCode());
        }
        return response.body();
    }

    // Token real del Keycloak local con el cliente bank-cli (usuarios de prueba del realm, solo local)
    private static String token(String user, String password) throws Exception {
        String form = "grant_type=password&client_id=bank-cli&username=" + URLEncoder.encode(user, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        String body = HTTP.send(HttpRequest.newBuilder(URI.create(KEYCLOAK))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        Matcher token = Pattern.compile("\"access_token\":\"([^\"]+)\"").matcher(body);
        if (!token.find()) {
            throw new IllegalStateException("Keycloak no entregó token para " + user);
        }
        return token.group(1);
    }

    private static int option(String[] args, String name, int defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return Integer.parseInt(args[i + 1]);
            }
        }
        return defaultValue;
    }
}
