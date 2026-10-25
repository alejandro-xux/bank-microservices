package com.alejandro.bank.accounts.corebanking.mainframe;

import com.alejandro.bank.accounts.corebanking.CoreBanking;
import com.alejandro.bank.accounts.corebanking.CoreBankingEntry;
import com.alejandro.bank.accounts.corebanking.PostingResult;
import com.alejandro.bank.accounts.domain.Currency;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// El cliente del core contra el mainframe emulado de verdad: el mismo WireMock y los mismos mappings que el compose
// (infra/core-banking-mock, BANK-76). Si un mapping o el codec cambian sin acuerdo, esta prueba lo nota
class MainframeCoreBankingIntegrationTest {

    static final GenericContainer<?> CORE = new GenericContainer<>("wiremock/wiremock:3.13.2")
            .withCommand("--global-response-templating", "--disable-banner")
            .withCopyFileToContainer(MountableFile.forHostPath("../infra/core-banking-mock/mappings"), "/home/wiremock/mappings")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/__admin/health"));

    static {
        CORE.start();
    }

    // El mismo plazo que en config-repo
    private final CoreBanking core = MainframeConfig.client("http://" + CORE.getHost() + ":" + CORE.getMappedPort(8080), Duration.ofSeconds(2));

    @Test
    void aNormalPostingIsRegistered() {
        assertThat(core.post(entry(1, "100.00"))).isEqualTo(new PostingResult(PostingResult.Outcome.POSTED, "000"));
    }

    @Test
    void aRejectedPostingSaysWhy() {
        assertThat(core.post(entry(2, "100.13"))).isEqualTo(new PostingResult(PostingResult.Outcome.REJECTED, "057"));
    }

    @Test
    void whenTheCoreIsDownTheResultIsUnknown() {
        assertThat(core.post(entry(3, "100.98")).outcome()).isEqualTo(PostingResult.Outcome.UNKNOWN);
    }

    @Test
    void aSlowCoreIsUnknownAtTheDeadlineNotRejected() {
        long start = System.nanoTime();

        PostingResult result = core.post(entry(4, "100.99"));

        // El core sí lo registra (responde a los 5 s): tratarlo como rechazo devolvería un dinero que ya se movió
        assertThat(result.outcome()).isEqualTo(PostingResult.Outcome.UNKNOWN);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(3500));
    }

    @Test
    void theQueryTellsWhatHappened() {
        assertThat(core.query(entry(4, "100.99")).outcome()).isEqualTo(PostingResult.Outcome.POSTED);
        assertThat(core.query(entry(3, "100.98")).outcome()).isEqualTo(PostingResult.Outcome.NOT_FOUND);
        assertThat(core.query(entry(2, "100.13")).outcome()).isEqualTo(PostingResult.Outcome.REJECTED);
    }

    @Test
    void anUnreachableCoreIsUnknown() {
        CoreBanking nowhere = MainframeConfig.client("http://localhost:1", Duration.ofSeconds(2));

        assertThat(nowhere.post(entry(5, "100.00")).outcome()).isEqualTo(PostingResult.Outcome.UNKNOWN);
    }

    private static CoreBankingEntry entry(long operation, String amount) {
        return new CoreBankingEntry(operation, Instant.parse("2026-10-25T14:33:12Z"), "001-0000000001-6", "001-0000000003-2",
                Currency.PEN, new BigDecimal(amount));
    }
}
