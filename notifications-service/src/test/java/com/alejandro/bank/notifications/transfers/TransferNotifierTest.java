package com.alejandro.bank.notifications.transfers;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferNotifierTest {

    @Test
    void accountNumbersAreMaskedInTheLog() {
        assertThat(TransferNotifier.masked("001-0000000003-2")).isEqualTo("001-******0003-2");
        assertThat(TransferNotifier.masked("raro")).isEqualTo("***");
    }
}
