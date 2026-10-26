-- fraud-service (BANK-107): los eventos ya revisados. Kafka entrega al menos una vez; la clave primaria hace que el
-- mismo evento repetido no se revise dos veces (consumidor idempotente, como notifications en BANK-72). El resumen
-- queda en JSON para auditoría: qué transferencia se revisó, de qué cuenta y por cuánto (sin datos personales)
CREATE TABLE processed_event (
    event_id     UUID        PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    summary      JSONB       NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);
