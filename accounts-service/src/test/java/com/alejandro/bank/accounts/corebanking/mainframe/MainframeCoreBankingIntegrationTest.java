package com.alejandro.bank.accounts.corebanking.mainframe;

import com.alejandro.bank.accounts.corebanking.CoreBanking;
import com.alejandro.bank.accounts.corebanking.CoreBankingEntry;
import com.alejandro.bank.accounts.corebanking.PostingResult;
import com.alejandro.bank.accounts.domain.Currency;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// El cliente del core contra el mainframe emulado de verdad: la misma imagen (WireMock con estado, BANK-90) y los
// mismos mappings que el compose (infra/core-banking-mock, BANK-76). Si un mapping o el codec cambian sin acuerdo,
// esta prueba lo nota
class MainframeCoreBankingIntegrationTest {

    static final GenericContainer<?> CORE = new GenericContainer<>(
            new ImageFromDockerfile("bank-core-banking-mock-test", false)
                    .withFileFromPath("Dockerfile", Path.of("../infra/core-banking-mock/Dockerfile")))
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
        // El core recuerda por id-op (BANK-90): la consulta responde lo que pasó con ESE asiento, no por el monto
        assertThat(core.query(entry(41, "100.00")).outcome()).isEqualTo(PostingResult.Outcome.NOT_FOUND);
        core.post(entry(41, "100.00"));
        assertThat(core.query(entry(41, "100.00")).outcome()).isEqualTo(PostingResult.Outcome.POSTED);

        core.post(entry(42, "100.13"));
        assertThat(core.query(entry(42, "100.13")).outcome()).isEqualTo(PostingResult.Outcome.REJECTED);

        core.post(entry(43, "100.98"));
        assertThat(core.query(entry(43, "100.98")).outcome()).isEqualTo(PostingResult.Outcome.NOT_FOUND);
    }

    @Test
    void theSamePostingTwiceIsRegisteredOnceAndAnsweredTheSame() {
        assertThat(core.post(entry(44, "100.13")).outcome()).isEqualTo(PostingResult.Outcome.REJECTED);
        assertThat(core.post(entry(44, "100.13")).outcome()).isEqualTo(PostingResult.Outcome.REJECTED);
        // .99 tarda 5 s la primera vez (el plazo es 2 s: desconocido), pero el core ya lo registró: el reenvío
        // responde enseguida lo mismo
        assertThat(core.post(entry(45, "100.99")).outcome()).isEqualTo(PostingResult.Outcome.UNKNOWN);
        assertThat(core.post(entry(45, "100.99")).outcome()).isEqualTo(PostingResult.Outcome.POSTED);
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
