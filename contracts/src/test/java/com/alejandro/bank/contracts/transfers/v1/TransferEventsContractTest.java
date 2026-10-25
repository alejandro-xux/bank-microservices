package com.alejandro.bank.contracts.transfers.v1;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

// Protege el evento EN EL CABLE (BANK-68), como CustomersContractTest. Un evento vive en el topic días
// (7 en Event Hubs Standard): un consumidor nuevo puede leer mensajes escritos por una versión vieja del
// productor, y al revés. Cambiar un número o un tipo los dejaría leyendo basura sin ningún error
class TransferEventsContractTest {

    // Un TransferCompleted de Ana (001-…1-6) a Luis (001-…3-2) por 150.00 PEN, tal como lo publica hoy
    // accounts-service. Cada campo empieza con (número << 3 | tipo): 0a = campo 1 texto, 12 = campo 2 …
    // 52 = campo 10 texto, 5a = campo 11 mensaje (Timestamp)
    private static final String ANA_TO_LUIS_ON_THE_WIRE =
            "0a2437633965363637392d373432352d343064652d393434622d653037666331663930616537"
                    + "122433663262386331642d356534612d346236632d396437652d386631613262336334643565"
                    + "1a2437653036633339372d393763352d343339632d396466662d326166613235373535643236"
                    + "22103030312d303030303030303030312d36"
                    + "2a2431383036313534332d313439652d343730372d613833332d666135363530396533383362"
                    + "32103030312d303030303030303030332d32"
                    + "3a2463313561343432642d353134362d343731652d383830342d373436663462343232353362"
                    + "422430386433386133622d633339382d343866622d626533362d336436613164646537616432"
                    + "4a063135302e3030"
                    + "520350454e"
                    + "5a0608c0def7d606";

    private static TransferCompleted anaToLuis() {
        return TransferCompleted.newBuilder()
                .setEventId("7c9e6679-7425-40de-944b-e07fc1f90ae7")
                .setTransferId("3f2b8c1d-5e4a-4b6c-9d7e-8f1a2b3c4d5e")
                .setFromAccountId("7e06c397-97c5-439c-9dff-2afa25755d26").setFromAccountNumber("001-0000000001-6")
                .setToAccountId("18061543-149e-4707-a833-fa56509e383b").setToAccountNumber("001-0000000003-2")
                .setFromCustomerId("c15a442d-5146-471e-8804-746f4b42253b")
                .setToCustomerId("08d38a3b-c398-48fb-be36-3d6a1dde7ad2")
                .setAmount("150.00").setCurrency("PEN")
                .setCompletedAt(Timestamp.newBuilder().setSeconds(1792929600L))
                .build();
    }

    @Test
    void anEventPublishedByTheCurrentVersionIsStillReadTheSameWay() throws Exception {
        TransferCompleted read = TransferCompleted.parseFrom(HexFormat.of().parseHex(ANA_TO_LUIS_ON_THE_WIRE));

        assertThat(read).isEqualTo(anaToLuis());
        assertThat(read.getAmount()).isEqualTo("150.00");
        assertThat(read.getCompletedAt().getSeconds()).isEqualTo(1792929600L);
    }

    @Test
    void theCurrentVersionWritesExactlyTheSameBytes() {
        assertThat(HexFormat.of().formatHex(anaToLuis().toByteArray())).isEqualTo(ANA_TO_LUIS_ON_THE_WIRE);
    }

    @Test
    void fieldNumbersNeverChange() {
        assertThat(TransferCompleted.EVENT_ID_FIELD_NUMBER).isEqualTo(1);
        assertThat(TransferCompleted.TRANSFER_ID_FIELD_NUMBER).isEqualTo(2);
        assertThat(TransferCompleted.FROM_ACCOUNT_ID_FIELD_NUMBER).isEqualTo(3);
        assertThat(TransferCompleted.FROM_ACCOUNT_NUMBER_FIELD_NUMBER).isEqualTo(4);
        assertThat(TransferCompleted.TO_ACCOUNT_ID_FIELD_NUMBER).isEqualTo(5);
        assertThat(TransferCompleted.TO_ACCOUNT_NUMBER_FIELD_NUMBER).isEqualTo(6);
        assertThat(TransferCompleted.FROM_CUSTOMER_ID_FIELD_NUMBER).isEqualTo(7);
        assertThat(TransferCompleted.TO_CUSTOMER_ID_FIELD_NUMBER).isEqualTo(8);
        assertThat(TransferCompleted.AMOUNT_FIELD_NUMBER).isEqualTo(9);
        assertThat(TransferCompleted.CURRENCY_FIELD_NUMBER).isEqualTo(10);
        assertThat(TransferCompleted.COMPLETED_AT_FIELD_NUMBER).isEqualTo(11);
    }

    @Test
    void theEventCarriesNoPersonalData() {
        // Decisión D3 del spike BANK-66: el correo, el nombre o el documento NO viajan en el topic.
        // Agregar un campo hace fallar esta prueba a propósito: obliga a revisar esa regla en el PR
        assertThat(TransferCompleted.getDescriptor().getFields())
                .extracting(FieldDescriptor::getName)
                .containsExactly("event_id", "transfer_id",
                        "from_account_id", "from_account_number", "to_account_id", "to_account_number",
                        "from_customer_id", "to_customer_id", "amount", "currency", "completed_at");
    }

    @Test
    void aFieldAddedByANewerProducerIsIgnoredNotAnError() throws Exception {
        // Una versión futura agrega el campo 12 (texto "x"): un consumidor de hoy sigue leyendo el resto
        byte[] fromTheFuture = HexFormat.of().parseHex(ANA_TO_LUIS_ON_THE_WIRE + "620178");

        TransferCompleted read = TransferCompleted.parseFrom(fromTheFuture);

        assertThat(read.getEventId()).isEqualTo("7c9e6679-7425-40de-944b-e07fc1f90ae7");
        assertThat(read.getAmount()).isEqualTo("150.00");
    }

    @Test
    void anEmptyMessageIsReadWithoutErrorSoTheConsumerMustValidate() throws Exception {
        // proto3 no tiene campos obligatorios (spike BANK-66, K8): cero bytes son un TransferCompleted
        // "válido" con todo vacío. Por eso notifications-service valida event_id y amount (BANK-72)
        TransferCompleted empty = TransferCompleted.parseFrom(new byte[0]);

        assertThat(empty.getEventId()).isEmpty();
        assertThat(empty.getAmount()).isEmpty();
    }

    @Test
    void theTopicsInTheConstantsAreTheOnesTheInfrastructureCreates() throws Exception {
        // Con la creación automática apagada (BANK-67), un topic que el script no crea no existe
        String script = Files.readString(Path.of("../infra/kafka/create-topics.sh"));

        assertThat(script).contains("create " + TransferTopics.TOPIC + " ")
                .contains("create " + TransferTopics.DEAD_LETTER_TOPIC + " ");
        assertThat(TransferTopics.DEAD_LETTER_TOPIC).isEqualTo("bank.transfers.v1.DLT");
    }
}
