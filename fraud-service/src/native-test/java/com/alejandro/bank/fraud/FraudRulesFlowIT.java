package com.alejandro.bank.fraud;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

// Las reglas de fraude de punta a punta contra el binario nativo (testNative, workflow native.yml). El recurso se
// declara aquí también: Quarkus no ve el de la clase padre desde src/native-test (BANK-107)
@QuarkusTestResource(FraudTestResource.class)
@QuarkusIntegrationTest
class FraudRulesFlowIT extends FraudRulesFlowTest {
}
