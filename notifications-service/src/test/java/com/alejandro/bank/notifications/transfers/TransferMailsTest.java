package com.alejandro.bank.notifications.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// BANK-72: qué dice cada aviso
class TransferMailsTest {

    // 25/10/2026 09:00 en Lima (UTC-5)
    private static final TransferCompleted EVENT = TransferCompleted.newBuilder()
            .setTransferId("f529e69b-c753-411e-97a6-d47a64f08f07")
            .setFromAccountNumber("001-0000000001-6").setToAccountNumber("001-0000000003-2")
            .setAmount("25.00").setCurrency("PEN")
            .setCompletedAt(Timestamp.newBuilder().setSeconds(1792936800L))
            .build();

    @Test
    void theSenderIsToldWhatLeftHerAccount() {
        TransferMails.Mail mail = TransferMails.of(EVENT, Recipient.SENDER, "Ana Quispe");

        assertThat(mail.subject()).isEqualTo("Enviaste 25.00 PEN");
        assertThat(mail.text()).startsWith("Hola Ana Quispe:")
                .contains("Enviaste 25.00 PEN desde tu cuenta 001-******0001-6 a la cuenta 001-******0003-2.")
                .contains("Fecha: 25/10/2026 09:00 (hora de Lima)")
                .contains("Operación: f529e69b-c753-411e-97a6-d47a64f08f07");
    }

    @Test
    void theReceiverIsToldWhatArrived() {
        TransferMails.Mail mail = TransferMails.of(EVENT, Recipient.RECEIVER, "Luis Rojas");

        assertThat(mail.subject()).isEqualTo("Recibiste 25.00 PEN");
        assertThat(mail.text()).contains("Recibiste 25.00 PEN en tu cuenta 001-******0003-2 desde la cuenta 001-******0001-6.");
    }

    @Test
    void betweenOwnAccountsItIsOneMessageNotTwoContradictoryOnes() {
        TransferMails.Mail mail = TransferMails.of(EVENT, Recipient.SELF, "Ana Quispe");

        assertThat(mail.subject()).isEqualTo("Moviste 25.00 PEN entre tus cuentas");
        assertThat(mail.text()).contains("Moviste 25.00 PEN de tu cuenta 001-******0001-6 a tu cuenta 001-******0003-2.");
    }

    @Test
    void fullAccountNumbersNeverAppear() {
        for (Recipient recipient : Recipient.values()) {
            assertThat(TransferMails.of(EVENT, recipient, "X").text())
                    .doesNotContain("0000000001").doesNotContain("0000000003");
        }
        assertThat(TransferMails.masked("raro")).isEqualTo("***");
    }
}
