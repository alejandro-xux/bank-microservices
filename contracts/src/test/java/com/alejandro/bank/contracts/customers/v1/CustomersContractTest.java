package com.alejandro.bank.contracts.customers.v1;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

// Protege el contrato EN EL CABLE (BANK-60). Renombrar un campo ya lo detecta el compilador de quien lo usa;
// cambiar su número o su tipo no, y dos servicios con versiones distintas dejarían de entenderse en
// silencio. Estas pruebas fallan en ese caso
class CustomersContractTest {

    private static final String ANA = "c15a442d-5146-471e-8804-746f4b42253b";

    // Un CustomerSummary { id: ANA, status: ACTIVE } tal como lo mandaría hoy customers-service:
    //   0a 24 <36 bytes del id>  →  campo 1 (texto), largo 36
    //   10 01                    →  campo 2 (varint), valor 1 = ACTIVE
    private static final String ANA_ACTIVE_ON_THE_WIRE =
            "0a2463313561343432642d353134362d343731652d383830342d3734366634623432323533621001";

    @Test
    void aMessageSentByTheCurrentVersionIsStillReadTheSameWay() throws Exception {
        CustomerSummary read = CustomerSummary.parseFrom(HexFormat.of().parseHex(ANA_ACTIVE_ON_THE_WIRE));

        assertThat(read.getId()).isEqualTo(ANA);
        assertThat(read.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    void theCurrentVersionWritesExactlyTheSameBytes() {
        CustomerSummary summary = CustomerSummary.newBuilder().setId(ANA).setStatus(CustomerStatus.ACTIVE).build();

        assertThat(HexFormat.of().formatHex(summary.toByteArray())).isEqualTo(ANA_ACTIVE_ON_THE_WIRE);
    }

    @Test
    void fieldNumbersNeverChange() {
        assertThat(CustomerSummary.ID_FIELD_NUMBER).isEqualTo(1);
        assertThat(CustomerSummary.STATUS_FIELD_NUMBER).isEqualTo(2);
        assertThat(GetCustomerRequest.ID_FIELD_NUMBER).isEqualTo(1);
        // BANK-71
        assertThat(GetContactRequest.ID_FIELD_NUMBER).isEqualTo(1);
        assertThat(CustomerContact.ID_FIELD_NUMBER).isEqualTo(1);
        assertThat(CustomerContact.NAME_FIELD_NUMBER).isEqualTo(2);
        assertThat(CustomerContact.EMAIL_FIELD_NUMBER).isEqualTo(3);
    }

    @Test
    void enumValuesNeverChange() {
        assertThat(CustomerStatus.CUSTOMER_STATUS_UNSPECIFIED.getNumber()).isZero();
        assertThat(CustomerStatus.ACTIVE.getNumber()).isEqualTo(1);
        assertThat(CustomerStatus.BLOCKED.getNumber()).isEqualTo(2);
    }

    @Test
    void anUnknownStatusFromANewerVersionIsReadAsUnrecognizedNotAnError() throws Exception {
        // Una versión futura agrega CLOSED = 3: este servicio todavía no lo conoce
        byte[] fromTheFuture = HexFormat.of().parseHex("1003");

        CustomerSummary read = CustomerSummary.parseFrom(fromTheFuture);

        assertThat(read.getStatus()).isEqualTo(CustomerStatus.UNRECOGNIZED);
        assertThat(read.getStatusValue()).isEqualTo(3);
    }

    @Test
    void theServiceExposesExactlyTheAgreedMethods() {
        assertThat(CustomersServiceGrpc.getServiceDescriptor().getName()).isEqualTo("bank.customers.v1.CustomersService");
        assertThat(CustomersServiceGrpc.getServiceDescriptor().getMethods())
                .extracting(method -> method.getFullMethodName())
                .containsExactlyInAnyOrder(
                        "bank.customers.v1.CustomersService/GetCustomer",
                        "bank.customers.v1.CustomersService/GetMyCustomer",
                        "bank.customers.v1.CustomersService/GetContact");
    }
}
