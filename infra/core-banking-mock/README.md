# core-banking-mock

Un "mainframe" local (BANK-76, spike BANK-74): recibe asientos contables en **tramas de largo fijo** y responde igual, como un core bancario legado que no controlamos. Es WireMock con los mappings de `mappings/`; no tiene estado ni base de datos.

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

## Las reglas (por los céntimos del monto)

| Céntimos | Asiento | Consulta |
|---|---|---|
| `.13` | `057 RC` (rechazo) | `057 RC` |
| `.99` | `000 OK`, pero **a los 5 s** (más que el timeout de accounts): queda registrado | `000 OK` |
| `.98` | HTTP 500 `091 CORE NO DISPONIBLE`: **no** queda registrado | `025 NF` |
| cualquier otro | `000 OK` | `000 OK` |

**Límite:** la consulta responde según estas reglas, sin recordar nada. Si accounts-service se cae justo antes de mandar un asiento de monto normal, la consulta dirá "registrado" aunque nunca llegó (un core real diría "no existe"). El camino "no existe → reenviar" se prueba con `.98`.

## Probar a mano

```bash
curl -s -H 'Content-Type: text/plain' --data-binary \
  'TRX202610250933120000000123450010000000001600100000000032PEN000000000010000' \
  http://localhost:8089/core/asientos
# → 000TRX20261025093312000000012345PEN000000000010000OK
```

Los mappings se recargan al reiniciar el contenedor (`docker compose -f infra/docker-compose.yml restart core-banking-mock`).
