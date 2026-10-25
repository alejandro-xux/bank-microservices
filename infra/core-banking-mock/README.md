# core-banking-mock

Un "mainframe" local (BANK-76, spike BANK-74): recibe asientos contables en **tramas de largo fijo** y responde igual, como un core bancario legado que no controlamos. Es WireMock con los mappings de `mappings/` y la extensión de estado de WireMock (BANK-90): **recuerda cada asiento por su id-op**, como un core real. La imagen se arma con el `Dockerfile` de esta carpeta, que baja el jar de la extensión de Maven Central y lo verifica con su sha256. La usan el compose y las pruebas.

En un banco real, el core se alcanza por IBM MQ o por z/OS Connect, que exponen programas CICS cuyos datos están descritos por *copybooks* COBOL de largo fijo. accounts-service habla con este mock por HTTP; cambiar a MQ solo cambiaría su adaptador (el Anti-Corruption Layer), no su dominio.

## Las tramas

Todo en ASCII, sin separadores. Los números van con ceros a la izquierda y los montos, en céntimos (dos decimales implícitos, como un `PIC 9(13)V99`).

```
ASIENTO  POST /core/asientos   (75 caracteres)
  TRX 20261025093312 000000012345 00100000000016 00100000000032 PEN 000000000010000
  op  fecha y hora   id-op        cuenta origen  cuenta destino mon monto (100.00)

CONSULTA POST /core/consultas  (47 caracteres)
  QRY 20261025093312 000000012345 PEN 000000000010000

RESPUESTA                      (52 caracteres)
  000 TRX 20261025093312 000000012345 PEN 000000000010000 OK
  cód op  fecha y hora   id-op        mon monto           estado
```

| Código | Estado | Significa |
|---|---|---|
| `000` | `OK` | Registrado |
| `057` | `RC` | Rechazado por el core |
| `025` | `NF` | No existe (solo en la consulta: el core nunca lo registró) |
| `091` | — | Core no disponible (HTTP 500, el cuerpo no es una trama) |

Una trama de largo incorrecto no coincide con ningún mapping y recibe 404: el mock no adivina.

## Las reglas

El **asiento** decide por los céntimos del monto, y el core **recuerda** lo que respondió para ese id-op:

| Céntimos | Asiento | Queda registrado |
|---|---|---|
| `.13` | `057 RC` (rechazo) | sí, como rechazo |
| `.99` | `000 OK`, pero **a los 5 s** (más que el timeout de accounts) | sí, **al llegar**: el core registra y después contesta tarde |
| `.98` | HTTP 500 `091 CORE NO DISPONIBLE` | **no** |
| cualquier otro | `000 OK` | sí |

- **La consulta** responde lo que quedó registrado para ese id-op (`000 OK` o `057 RC`), y `025 NF` si el asiento **nunca llegó**, sea cual sea el monto. Antes de BANK-90 respondía por el monto: un asiento que nunca llegó con un monto normal decía "registrado".
- **El mismo id-op otra vez** responde lo que se registró la primera vez, sin registrar de nuevo ni demorar (idempotente, como un core real). Un `.98` no queda registrado, así que el reenvío vuelve a fallar.
- **Para las pruebas:** `POST /__estado/borrar` olvida todos los asientos. `PostgresIntegrationTest` lo llama antes de cada prueba. El estado vive en memoria: reiniciar el contenedor también lo borra.

## Probar a mano

```bash
curl -s -H 'Content-Type: text/plain' --data-binary \
  'TRX202610250933120000000123450010000000001600100000000032PEN000000000010000' \
  http://localhost:8089/core/asientos
# → 000TRX20261025093312000000012345PEN000000000010000OK
```

Los mappings se recargan al reiniciar el contenedor (`docker compose -f infra/docker-compose.yml restart core-banking-mock`).
