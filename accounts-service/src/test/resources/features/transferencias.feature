# language: es
# Las reglas de una transferencia, escritas para leerlas sin saber Java (BANK-87). Cada escenario corre contra la API
# real de accounts-service, con PostgreSQL, Kafka y el mainframe emulado de verdad. El mainframe decide por los
# céntimos del monto: .13 lo rechaza y .99 responde tarde (pero lo registra). Después de cada escenario se comprueba
# que el banco no creó ni perdió dinero: saldos + transferencias pendientes = lo que había al empezar

Característica: Transferir dinero entre cuentas
  Como cliente del banco
  Quiero mover dinero a otra cuenta
  Para pagar o ahorrar, sabiendo siempre en qué quedó mi dinero

  Antecedentes:
    Dado que Ana tiene una cuenta en soles con S/ 1000.00
    Y que Luis tiene una cuenta en soles con S/ 1000.00

  Escenario: El core bancario registra la transferencia
    Cuando Ana transfiere S/ 101.00 a la cuenta de Luis
    Entonces la transferencia queda "COMPLETED"
    Y la cuenta de Ana tiene S/ 899.00
    Y la cuenta de Luis tiene S/ 1101.00
    Y queda el evento "TransferCompleted" para publicar

  Escenario: Fondos insuficientes
    Cuando Ana transfiere S/ 5000.00 a la cuenta de Luis
    Entonces la transferencia se rechaza con 422 "Fondos insuficientes"
    Y la cuenta de Ana tiene S/ 1000.00
    Y la cuenta de Luis tiene S/ 1000.00
    Y la misma clave sirve para reintentar con S/ 102.00

  Escenario: Las cuentas tienen monedas distintas
    Dado que Luis también tiene una cuenta en dólares con US$ 500.00
    Cuando Ana transfiere S/ 103.00 a la cuenta en dólares de Luis
    Entonces la transferencia se rechaza con 422 "Transferencia rechazada"
    Y el motivo dice "monedas distintas"
    Y la cuenta de Ana tiene S/ 1000.00

  Escenario: El core bancario rechaza la transferencia
    Cuando Ana transfiere S/ 104.13 a la cuenta de Luis
    Entonces la transferencia queda "FAILED" con el motivo "057"
    Y la cuenta de Ana tiene S/ 1000.00
    Y la cuenta de Luis tiene S/ 1000.00
    Y la cuenta de Ana tiene los movimientos "DEBIT, REVERSAL"
    Y queda el evento "TransferFailed" para publicar

  Escenario: El core bancario no responde a tiempo
    Cuando Ana transfiere S/ 105.99 a la cuenta de Luis
    Entonces la transferencia queda "PENDING"
    Y la cuenta de Ana tiene S/ 894.01
    Y la cuenta de Luis tiene S/ 1000.00
    Cuando pasa el recuperador
    Entonces la transferencia queda "COMPLETED"
    Y la cuenta de Luis tiene S/ 1105.99
