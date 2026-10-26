package com.alejandro.bank.fraud;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

// Health y seguridad contra el binario nativo (testNative, workflow native.yml)
// El recurso se declara otra vez aquí: Quarkus busca los @QuarkusTestResource en las clases de src/native-test, y la
// anotación de la clase padre (src/test) no la ve. Sin esto, el binario arranca sin base, sin Kafka ni llave y se cae
@QuarkusTestResource(FraudTestResource.class)
@QuarkusIntegrationTest
class HealthIT extends HealthTest {
}
