# contracts

Contratos de la comunicación **interna** entre servicios (gRPC). La API pública (gateway, apps, Swagger) sigue siendo REST.

| Archivo | Servidor | Cliente |
|---|---|---|
| `src/main/proto/customers.proto` | customers-service (:9091) | accounts-service |

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

## Qué lo protege

- **El compilador:** renombrar o quitar un campo rompe la compilación de quien lo usa.
- **`CustomersContractTest`:** fija los números de campo y del enum, y lee un mensaje guardado en bytes, el mismo que manda la versión actual. Cambiar un número rompe la prueba.
- **Pendiente para CI (E9):** `buf breaking`, que compara el `.proto` con el de `main` y rechaza los cambios incompatibles en el PR.
