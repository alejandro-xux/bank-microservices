-- fraud-service (BANK-108): cada revisión de una transferencia, con las reglas que dispararon y el resultado. Es el
-- registro de auditoría y también la historia de la cuenta que usan las reglas (frecuencia, promedio, destinos).
-- event_id NO es único aquí a propósito: el que descarta los duplicados es processed_event (BANK-107), en la misma
-- transacción; un solo lugar decide si un evento ya se revisó. La historia empieza con BANK-108: los eventos de antes
-- solo dejaron su resumen en processed_event
CREATE TABLE fraud_review (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id        UUID          NOT NULL REFERENCES processed_event (event_id),
    transfer_id     UUID          NOT NULL,
    from_account_id UUID          NOT NULL,
    to_account_id   UUID          NOT NULL,
    amount          NUMERIC(19,2) NOT NULL,
    currency        CHAR(3)       NOT NULL,
    -- Cuándo se registró la transferencia (el del evento): la regla de frecuencia mira este, no reviewed_at
    completed_at    TIMESTAMPTZ   NOT NULL,
    -- Las reglas que dispararon (SuspicionRule); vacío si ninguna
    rules           VARCHAR(40)[] NOT NULL,
    suspicious      BOOLEAN       NOT NULL,
    reviewed_at     TIMESTAMPTZ   NOT NULL
);

-- Frecuencia: las transferencias de una cuenta en una ventana de tiempo
CREATE INDEX fraud_review_account_time ON fraud_review (from_account_id, completed_at);
-- Destino nuevo: si la cuenta ya transfirió a ese destino
CREATE INDEX fraud_review_account_destination ON fraud_review (from_account_id, to_account_id);
-- Los avisos, para mirarlos
CREATE INDEX fraud_review_suspicious ON fraud_review (reviewed_at) WHERE suspicious;
