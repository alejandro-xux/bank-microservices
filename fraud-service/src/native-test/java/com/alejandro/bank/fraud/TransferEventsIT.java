package com.alejandro.bank.fraud;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

// Las mismas pruebas del consumidor contra el binario nativo (testNative, workflow native.yml): lo que en JVM funciona
// por reflexión y en nativo solo si se registró al compilar (el resumen de EventSummary) se prueba aquí
// El recurso se declara otra vez aquí: Quarkus busca los @QuarkusTestResource en las clases de src/native-test, y la
// anotación de la clase padre (src/test) no la ve. Sin esto, el binario arranca sin base, sin Kafka ni llave y se cae
@QuarkusTestResource(FraudTestResource.class)
@QuarkusIntegrationTest
class TransferEventsIT extends TransferEventsTest {
}
