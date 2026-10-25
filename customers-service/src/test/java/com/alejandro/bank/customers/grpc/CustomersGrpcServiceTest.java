package com.alejandro.bank.customers.grpc;

import com.alejandro.bank.contracts.customers.v1.CustomerStatus;
import com.alejandro.bank.contracts.customers.v1.CustomerSummary;
import com.alejandro.bank.contracts.customers.v1.CustomersServiceGrpc;
import com.alejandro.bank.contracts.customers.v1.GetCustomerRequest;
import com.alejandro.bank.contracts.customers.v1.GetMyCustomerRequest;
import com.alejandro.bank.customers.domain.Customer;
import com.alejandro.bank.customers.exception.CustomerNotFoundException;
import com.alejandro.bank.customers.repository.CustomerRepository;
import com.alejandro.bank.customers.service.CustomerService;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureInProcessTransport;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Servidor gRPC (BANK-61) en memoria: misma seguridad y mismas reglas que REST.
// El JwtDecoder es simulado (la validación real del token contra Keycloak se probó en el spike BANK-59)
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureInProcessTransport
class CustomersGrpcServiceTest {

    private static final String ANA = "c15a442d-5146-471e-8804-746f4b42253b";
    private static final String ANA_OID = "4f8c2a1e-6b3d-4c9a-9e7f-2d5b8a1c3e01";
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @MockitoBean
    CustomerRepository customerRepository;

    @MockitoBean
    CustomerService customerService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    GrpcChannelFactory channels;

    @BeforeEach
    void tokens() {
        when(jwtDecoder.decode("token-asesor")).thenReturn(jwt("token-asesor", "7b2e9d4c", "ADMIN"));
        when(jwtDecoder.decode("token-ana")).thenReturn(jwt("token-ana", ANA_OID, "CUSTOMER"));
        when(jwtDecoder.decode("token-sin-vinculo")).thenReturn(jwt("token-sin-vinculo", "5e5e5e5e", "CUSTOMER"));
        when(jwtDecoder.decode("token-falso")).thenThrow(new BadJwtException("firma inválida"));
    }

    // ---- GetCustomer (solo asesor) ----

    @Test
    void adminGetsACustomerById() {
        when(customerService.findById(ANA)).thenReturn(Mono.just(customer(ANA, com.alejandro.bank.customers.domain.CustomerStatus.ACTIVE)));

        CustomerSummary summary = stub("token-asesor").getCustomer(GetCustomerRequest.newBuilder().setId(ANA).build());

        assertThat(summary.getId()).isEqualTo(ANA);
        assertThat(summary.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    void aBlockedCustomerComesAsBlocked() {
        when(customerService.findById("b1")).thenReturn(Mono.just(customer("b1", com.alejandro.bank.customers.domain.CustomerStatus.BLOCKED)));

        assertThat(stub("token-asesor").getCustomer(GetCustomerRequest.newBuilder().setId("b1").build()).getStatus())
                .isEqualTo(CustomerStatus.BLOCKED);
    }

    @Test
    void unknownCustomerIsNotFound() {
        when(customerService.findById("no-existe")).thenReturn(Mono.error(new CustomerNotFoundException("no-existe")));

        assertThat(statusOf(() -> stub("token-asesor").getCustomer(GetCustomerRequest.newBuilder().setId("no-existe").build())))
                .satisfies(status -> {
                    assertThat(status.getCode()).isEqualTo(Status.Code.NOT_FOUND);
                    assertThat(status.getDescription()).isEqualTo("El cliente no existe");
                });
    }

    @Test
    void customerCannotGetOtherCustomersById() {
        assertThat(statusOf(() -> stub("token-ana").getCustomer(GetCustomerRequest.newBuilder().setId(ANA).build())).getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);

        verify(customerService, never()).findById(any());
    }

    // ---- GetMyCustomer (solo cliente) ----

    @Test
    void customerGetsTheCustomerLinkedToHerToken() {
        when(customerService.findByIdentityId(ANA_OID)).thenReturn(Mono.just(customer(ANA, com.alejandro.bank.customers.domain.CustomerStatus.ACTIVE)));

        CustomerSummary me = stub("token-ana").getMyCustomer(GetMyCustomerRequest.getDefaultInstance());

        assertThat(me.getId()).isEqualTo(ANA);
    }

    @Test
    void customerWithoutLinkIsPermissionDenied() {
        when(customerService.findByIdentityId("5e5e5e5e")).thenReturn(Mono.empty());

        assertThat(statusOf(() -> stub("token-sin-vinculo").getMyCustomer(GetMyCustomerRequest.getDefaultInstance())).getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void adminHasNoCustomerOfHisOwn() {
        assertThat(statusOf(() -> stub("token-asesor").getMyCustomer(GetMyCustomerRequest.getDefaultInstance())).getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    // ---- token ----

    @Test
    void withoutTokenIsUnauthenticated() {
        CustomersServiceGrpc.CustomersServiceBlockingStub anonymous =
                CustomersServiceGrpc.newBlockingStub(channels.createChannel("customers"));

        assertThat(statusOf(() -> anonymous.getCustomer(GetCustomerRequest.newBuilder().setId(ANA).build())).getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void invalidTokenIsUnauthenticated() {
        assertThat(statusOf(() -> stub("token-falso").getCustomer(GetCustomerRequest.newBuilder().setId(ANA).build())).getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    // ---- operación ----

    @Test
    void healthIsAvailableWithoutTokenForTheProbes() {
        HealthCheckResponse health = HealthGrpc.newBlockingStub(channels.createChannel("customers"))
                .check(HealthCheckRequest.getDefaultInstance());

        assertThat(health.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
    }

    @Test
    void unexpectedErrorsDoNotExposeInternalDetails() {
        when(customerService.findById(ANA)).thenReturn(Mono.error(new IllegalStateException("Cosmos: 503 RU agotadas en bank-cosmos-alejandro")));

        Status status = statusOf(() -> stub("token-asesor").getCustomer(GetCustomerRequest.newBuilder().setId(ANA).build()));

        assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(status.getDescription()).isEqualTo("Error interno").doesNotContain("Cosmos");
    }

    @Test
    void whenTheDeadlinePassesTheServerStopsWorking() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(customerService.findById("lento")).thenReturn(Mono.<Customer>never().doOnCancel(() -> cancelled.set(true)));

        assertThat(statusOf(() -> stub("token-asesor").withDeadlineAfter(200, TimeUnit.MILLISECONDS)
                .getCustomer(GetCustomerRequest.newBuilder().setId("lento").build())).getCode())
                .isEqualTo(Status.Code.DEADLINE_EXCEEDED);

        // La cancelación llega al servidor justo después de vencer el plazo
        for (int i = 0; i < 20 && !cancelled.get(); i++) {
            Thread.sleep(50);
        }
        assertThat(cancelled).as("el Mono del servidor se canceló").isTrue();
    }

    // ---- utilidades ----

    private CustomersServiceGrpc.CustomersServiceBlockingStub stub(String token) {
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer " + token);
        return CustomersServiceGrpc.newBlockingStub(channels.createChannel("customers"))
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private static Status statusOf(Runnable call) {
        StatusRuntimeException[] thrown = new StatusRuntimeException[1];
        assertThatThrownBy(() -> {
            try {
                call.run();
            } catch (StatusRuntimeException ex) {
                thrown[0] = ex;
                throw ex;
            }
        }).isInstanceOf(StatusRuntimeException.class);
        return thrown[0].getStatus();
    }

    private static Customer customer(String id, com.alejandro.bank.customers.domain.CustomerStatus status) {
        return Customer.builder().id(id).status(status).identityId(ANA_OID).build();
    }

    private static Jwt jwt(String value, String oid, String role) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(oid).claim("oid", oid)
                .claim("roles", List.of(role)).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }
}
