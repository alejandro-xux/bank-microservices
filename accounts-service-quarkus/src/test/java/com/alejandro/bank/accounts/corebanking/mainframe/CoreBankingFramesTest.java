package com.alejandro.bank.accounts.corebanking.mainframe;

import com.alejandro.bank.accounts.corebanking.CoreBankingEntry;
import com.alejandro.bank.accounts.corebanking.PostingResult;
import com.alejandro.bank.accounts.domain.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// El formato de las tramas, fijado con tramas guardadas (BANK-77), como las pruebas de bytes de contracts: si un
// campo cambia de largo o de lugar, el core dejaría de entendernos en silencio. Estas pruebas fallan en ese caso
class CoreBankingFramesTest {

    // Transferencia 12345 de 100.00 PEN de 001-0000000001-6 a 001-0000000003-2, el 25/10/2026 a las 09:33:12 de Lima
    private static final CoreBankingEntry ENTRY = new CoreBankingEntry(12345, Instant.parse("2026-10-25T14:33:12Z"),
            "001-0000000001-6", "001-0000000003-2", Currency.PEN, new BigDecimal("100.00"));

    //   TRX | 20261025093312 | 000000012345 | 00100000000016 | 00100000000032 | PEN | 000000000010000
    private static final String POSTING = "TRX202610250933120000000123450010000000001600100000000032PEN000000000010000";
    //   QRY | 20261025093312 | 000000012345 | PEN | 000000000010000
    private static final String QUERY = "QRY20261025093312000000012345PEN000000000010000";

    @Test
    void thePostingFrameIsExactlyTheAgreedOne() {
        assertThat(POSTING).hasSize(CoreBankingFrames.POSTING_LENGTH);
        assertThat(CoreBankingFrames.posting(ENTRY)).isEqualTo(POSTING);
    }

    @Test
    void theQueryFrameIsExactlyTheAgreedOne() {
        assertThat(QUERY).hasSize(CoreBankingFrames.QUERY_LENGTH);
        assertThat(CoreBankingFrames.query(ENTRY)).isEqualTo(QUERY);
    }

    @Test
    void repliesAreReadIntoTheFourOutcomes() {
        assertThat(CoreBankingFrames.reply(reply("000", "TRX", "OK"), "TRX", 12345))
                .isEqualTo(new PostingResult(PostingResult.Outcome.POSTED, "000"));
        assertThat(CoreBankingFrames.reply(reply("057", "TRX", "RC"), "TRX", 12345))
                .isEqualTo(new PostingResult(PostingResult.Outcome.REJECTED, "057"));
        assertThat(CoreBankingFrames.reply(reply("025", "QRY", "NF"), "QRY", 12345))
                .isEqualTo(new PostingResult(PostingResult.Outcome.NOT_FOUND, "025"));
    }

    @Test
    void anythingThatIsNotTheExpectedReplyIsUnknownNeverARejection() {
        String good = reply("000", "TRX", "OK");
        assertThat(CoreBankingFrames.reply(good.substring(1), "TRX", 12345).outcome()).isEqualTo(PostingResult.Outcome.UNKNOWN);
        assertThat(CoreBankingFrames.reply("091 CORE NO DISPONIBLE", "TRX", 12345).outcome()).isEqualTo(PostingResult.Outcome.UNKNOWN);
        assertThat(CoreBankingFrames.reply(null, "TRX", 12345).outcome()).isEqualTo(PostingResult.Outcome.UNKNOWN);
        // La respuesta es de otra operación o de una consulta: no es la nuestra
        assertThat(CoreBankingFrames.reply(good, "TRX", 99999).outcome()).isEqualTo(PostingResult.Outcome.UNKNOWN);
        assertThat(CoreBankingFrames.reply(good, "QRY", 12345).outcome()).isEqualTo(PostingResult.Outcome.UNKNOWN);
        assertThat(CoreBankingFrames.reply(reply("000", "TRX", "ZZ"), "TRX", 12345).outcome()).isEqualTo(PostingResult.Outcome.UNKNOWN);
    }

    @Test
    void theFieldsAreTranslatedFromTheDomain() {
        assertThat(CoreBankingFrames.account("001-0000000003-2")).isEqualTo("00100000000032");
        assertThat(CoreBankingFrames.cents(new BigDecimal("100"))).isEqualTo("000000000010000");
        assertThat(CoreBankingFrames.cents(new BigDecimal("0.13"))).isEqualTo("000000000000013");
        assertThat(CoreBankingFrames.operation(1)).isEqualTo("000000000001");
    }

    @Test
    void valuesThatDoNotFitAreRejectedBeforeSendingAnything() {
        assertThatThrownBy(() -> CoreBankingFrames.account("001-000000001-6")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CoreBankingFrames.cents(new BigDecimal("-1.00"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CoreBankingFrames.cents(new BigDecimal("1.005"))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> CoreBankingFrames.cents(new BigDecimal("10000000000000.00"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CoreBankingFrames.operation(1_000_000_000_000L)).isInstanceOf(IllegalArgumentException.class);
    }

    // código | operación | 20261025093312 | 000000012345 | PEN | 000000000010000 | estado
    private static String reply(String code, String operation, String state) {
        return code + operation + "20261025093312" + "000000012345" + "PEN" + "000000000010000" + state;
    }
}
