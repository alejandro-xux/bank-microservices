# contracts

Contratos de la comunicación **interna** entre servicios: llamadas gRPC y eventos de Kafka. La API pública (gateway, apps, Swagger) sigue siendo REST.

| Archivo | Tipo | Quién lo produce o sirve | Quién lo usa |
|---|---|---|---|
| `src/main/proto/customers.proto` | gRPC | customers-service (:9091) | accounts-service |
| `src/main/proto/transfer_events.proto` | Evento en `bank.transfers.v1` | accounts-service (outbox) | notifications-service |

`TransferTopics` (Java) tiene los nombres del topic, del DLT y de los headers (`event-type`, `event-id`), para que productor y consumidor no los escriban a mano. Los topics los crea `infra/kafka/create-topics.sh`, no la app.

El código Java se genera aquí **una sola vez** (`./gradlew build`) y los servicios lo usan como dependencia:

```groovy
// settings.gradle del servicio
includeBuild('../contracts')
// build.gradle del servicio
implementation 'com.alejandro.bank:contracts'
```

El código generado queda en `build/` y no se sube al repo; lo que se versiona es el `.proto`.

## Cómo cambiar un contrato sin romper a nadie

En el binario viajan los **números** de los campos, no sus nombres. Un servicio con la versión anterior sigue leyendo los mensajes nuevos mientras se respeten estas reglas:

| Seguro | Rompe (o corrompe en silencio) |
|---|---|
| Agregar un campo con un número nuevo | Cambiar el número de un campo |
| Renombrar un campo (el número sigue igual) | Reutilizar el número de un campo borrado |
| Borrar un campo y marcar su número con `reserved` | Cambiar el tipo de un campo (`string` → `int32`) |
| Agregar un valor nuevo a un enum (le llega `UNRECOGNIZED` a quien no lo conoce) | Cambiar el número de un valor del enum, o usar el `0` para un estado real |

```proto
message CustomerSummary {
  reserved 3;            // campo borrado: nadie puede volver a usar el 3
  reserved "segment";
  string id = 1;
  CustomerStatus status = 2;
}
```

Si de verdad hay que romper, se crea el paquete siguiente (`bank.customers.v2`) **al lado** del actual, los clientes se pasan uno por uno y recién después se borra el `v1`.

### Lo distinto de los eventos
- **Viven días en el topic** (7 en Event Hubs Standard). Un consumidor nuevo lee mensajes escritos por una versión vieja del productor, y al revés: las reglas de arriba valen igual, pero no hay forma de "desplegar los dos a la vez".
- **Un cambio incompatible es un topic nuevo** (`bank.transfers.v2`), no solo un paquete nuevo. El productor publica en los dos mientras los consumidores se pasan.
- **Sin datos personales.** El evento lleva ids, números de cuenta y montos; quien necesite el correo o el nombre los pide al dueño del dato (`GetContact`). Un campo personal quedaría copiado en el topic toda su retención.
- **proto3 no tiene campos obligatorios.** Un mensaje vacío, o cero bytes, se lee sin error. El consumidor valida lo que necesita (`event_id`, `amount`) y manda al DLT lo que no sirve.

## Qué lo protege

- **El compilador:** renombrar o quitar un campo rompe la compilación de quien lo usa.
- **`CustomersContractTest`:** fija los números de campo y del enum, y lee un mensaje guardado en bytes, el mismo que manda la versión actual. Cambiar un número rompe la prueba.
- **`TransferEventsContractTest`:** lo mismo para `TransferCompleted` y `TransferFailed` (BANK-80), más la lista exacta de campos de cada uno (agregar uno obliga a revisar la regla de datos personales en el PR), que leer un `TransferFailed` como `TransferCompleted` da un error o datos corridos (por eso el tipo va en el header `event-type`) y que los topics de `TransferTopics` sean los que crea `create-topics.sh`.
- **`buf` en la CI (BANK-86, job `buf`, parte de `ci-ok`):**
  - `buf breaking` compara **todos** los `.proto` con los de `main` (reglas `WIRE_JSON`). Frena un campo renumerado, borrado o con otro tipo, y un RPC o un servicio quitado, aunque nadie haya escrito una prueba para ese mensaje.
  - `buf lint` exige las reglas `STANDARD`, salvo las que los `.proto` de hoy no cumplen. Están en `buf.yaml`, cada una con su motivo, y se arreglan juntas en un paquete `v2`.
- **Las dos defensas se complementan:**
  - buf cubre todo el contrato contra la versión anterior.
  - Las pruebas de bytes fijan cómo se ve **hoy** un mensaje en el cable y qué campos tiene, por ejemplo que no entren datos personales. Detectan algo que buf no ve: un campo nuevo que sí es compatible, pero que rompe una regla del negocio.
