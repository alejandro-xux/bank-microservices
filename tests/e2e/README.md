# Prueba de punta a punta (BANK-88)

El sistema entero como lo usa un cliente: login real en Keycloak, todo por el gateway, la transferencia, el mainframe emulado, Kafka y los correos en Mailpit. Hasta BANK-88, esta verificación se hacía a mano con `curl` en cada ticket.

## Requisitos
- El entorno local arriba: `infra/start-local.sh`, con los datos de prueba de `infra/seed-local.sh`.
- Nada más. La prueba no borra ni reinicia nada: hace transferencias de 1 a 10 soles desde la cuenta en soles de Ana hacia la de `001-0000000003-2`.

## Correrla
```bash
cd tests/e2e
./gradlew test                       # ~30 s
open build/karate-reports/karate-summary.html
# Contra otro entorno:
./gradlew test -Dgateway=http://... -Dkeycloak=http://... -Dmailpit=http://...
```

## Qué prueba (`src/test/resources/bank/transferencias.feature`)
| Escenario | Qué se espera |
|---|---|
| Sin token / cuenta ajena | 401 / 403 |
| El core registra | 201 → `GET /transfers/{id}` COMPLETED → "Enviaste" y "Recibiste" en Mailpit → los saldos cuadran |
| El core rechaza (`.13`) | 422 FAILED (057) → un solo correo, a Ana, "No pudimos completar…" → los saldos no cambian |
| El core no responde (`.99`) | 202 PENDING → COMPLETED cuando pasa el recuperador → los saldos cuadran |

- Las cuentas se buscan por la API (`/accounts/me` y, como asesor, `?customerId=`), así que sirve aunque los datos se hayan recreado con otros ids.
- Los montos son distintos en cada corrida, y la bandeja se filtra desde que empezó la prueba.
- Un servicio caído hace fallar la prueba en segundos, con el 503 del gateway: no se queda esperando (timeouts de 5 s y 10 s).
- Justo después de reiniciar un servicio, el gateway puede seguir respondiendo 503 unos 20 s: necesita ver el servicio otra vez en Eureka y que su circuit breaker se cierre. Se espera y se vuelve a correr.

En la CI llega en E10, cuando haya imágenes Docker de los servicios.
