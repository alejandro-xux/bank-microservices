package com.alejandro.bank.fraud;

import io.quarkus.test.junit.QuarkusIntegrationTest;

// Las mismas pruebas del consumidor contra el binario nativo (testNative, workflow native.yml): lo que en JVM funciona
// por reflexión y en nativo solo si se registró al compilar (el resumen de EventSummary) se prueba aquí
@QuarkusIntegrationTest
class TransferEventsIT extends TransferEventsTest {
}
