package com.alejandro.bank.accounts;

import com.alejandro.bank.contracts.customers.v1.CustomerStatus;
import com.alejandro.bank.contracts.customers.v1.CustomerSummary;
import com.alejandro.bank.contracts.customers.v1.CustomersServiceGrpc;
import com.alejandro.bank.contracts.customers.v1.GetCustomerRequest;
import com.alejandro.bank.contracts.customers.v1.GetMyCustomerRequest;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// customers-service simulado, como FakeCustomersService de accounts-service. GetCustomer responde por id; GetMyCustomer
// por el oid del token que llega en el header authorization (si accounts no reenvía el token, no sabe quién es y
// responde UNAUTHENTICATED, como el customers real)
public class FakeCustomers extends CustomersServiceGrpc.CustomersServiceImplBase {

    public static final String ANA = "c15a442d-5146-471e-8804-746f4b42253b";
    public static final String LUIS = "08d38a3b-c398-48fb-be36-3d6a1dde7ad2";
    public static final String BLOCKED = "11111111-0000-4000-8000-000000000001";
    public static final String MISSING = "11111111-0000-4000-8000-000000000002";
    public static final String DOWN = "11111111-0000-4000-8000-000000000003";
    public static final String SLOW = "11111111-0000-4000-8000-000000000004";

    static final Context.Key<String> AUTHORIZATION = Context.key("authorization");
    private static final Metadata.Key<String> AUTHORIZATION_HEADER =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Pattern OID = Pattern.compile("\"oid\"\\s*:\\s*\"([^\"]+)\"");
    private static final ScheduledExecutorService LATER = Executors.newSingleThreadScheduledExecutor();

    // El último header authorization que llegó: para comprobar que accounts reenvía el token del usuario
    volatile String lastAuthorization;

    @Override
    public void getCustomer(GetCustomerRequest request, StreamObserver<CustomerSummary> response) {
        lastAuthorization = AUTHORIZATION.get();
        switch (request.getId()) {
            case BLOCKED -> reply(response, BLOCKED, CustomerStatus.BLOCKED);
            case MISSING -> response.onError(Status.NOT_FOUND.asRuntimeException());
            case DOWN -> response.onError(Status.UNAVAILABLE.asRuntimeException());
            // Más que el plazo de 2 s: accounts corta antes y responde 503
            case SLOW -> LATER.schedule(() -> reply(response, SLOW, CustomerStatus.ACTIVE), 3, TimeUnit.SECONDS);
            default -> reply(response, request.getId(), CustomerStatus.ACTIVE);
        }
    }

    @Override
    public void getMyCustomer(GetMyCustomerRequest request, StreamObserver<CustomerSummary> response) {
        lastAuthorization = AUTHORIZATION.get();
        String oid = oid(lastAuthorization);
        if (oid == null) {
            response.onError(Status.UNAUTHENTICATED.asRuntimeException());
            return;
        }
        switch (oid) {
            case Tokens.ANA_OID -> reply(response, ANA, CustomerStatus.ACTIVE);
            case Tokens.LUIS_OID -> reply(response, LUIS, CustomerStatus.ACTIVE);
            case Tokens.CUSTOMERS_DOWN_OID -> response.onError(Status.UNAVAILABLE.asRuntimeException());
            default -> response.onError(Status.PERMISSION_DENIED.asRuntimeException());
        }
    }

    private static void reply(StreamObserver<CustomerSummary> response, String id, CustomerStatus status) {
        response.onNext(CustomerSummary.newBuilder().setId(id).setStatus(status).build());
        response.onCompleted();
    }

    // El oid del payload del JWT (sin verificar la firma: es un servidor falso)
    private static String oid(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String[] parts = authorization.substring(7).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        Matcher matcher = OID.matcher(payload);
        return matcher.find() ? matcher.group(1) : null;
    }

    // Deja el header authorization de cada llamada en el Context de gRPC, donde lo lee el servicio falso
    static class TokenCapture implements ServerInterceptor {
        @Override
        public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                           ServerCallHandler<Q, R> next) {
            Context context = Context.current().withValue(AUTHORIZATION, headers.get(AUTHORIZATION_HEADER));
            return Contexts.interceptCall(context, call, headers, next);
        }
    }
}
