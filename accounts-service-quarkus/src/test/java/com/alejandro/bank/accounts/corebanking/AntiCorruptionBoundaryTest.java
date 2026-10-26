package com.alejandro.bank.accounts.corebanking;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// La frontera del Anti-Corruption Layer (BANK-77): fuera de corebanking.mainframe, nadie conoce tramas ni HTTP del
// core; el resto del servicio solo usa CoreBanking, CoreBankingEntry y PostingResult. Si alguien importa el adaptador
// desde el dominio, el formato del legado empieza a filtrarse y esta prueba falla
class AntiCorruptionBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java/com/alejandro/bank/accounts");

    @Test
    void onlyTheConfigurationKnowsTheMainframeAdapter() throws IOException {
        try (Stream<Path> sources = Files.walk(MAIN)) {
            List<String> offenders = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains("/corebanking/mainframe/"))
                    .filter(path -> read(path).contains("import com.alejandro.bank.accounts.corebanking.mainframe."))
                    .map(path -> MAIN.relativize(path).toString())
                    .toList();

            assertThat(offenders).isEmpty();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
