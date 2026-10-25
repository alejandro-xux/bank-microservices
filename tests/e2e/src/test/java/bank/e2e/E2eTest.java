package bank.e2e;

import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Corre los escenarios de src/test/resources/bank contra el entorno local (BANK-88). Uno por uno: comparten las mismas
// cuentas de Ana y los saldos se comparan antes y después. El reporte queda en build/karate-reports
class E2eTest {

    @Test
    void elSistemaComoLoUsaUnCliente() {
        Results results = Runner.path("classpath:bank").parallel(1);
        assertEquals(0, results.getFailCount(), results.getErrorMessages());
    }
}
