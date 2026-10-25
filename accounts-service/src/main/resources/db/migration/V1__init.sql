-- Esquema inicial de accounts-service (BANK-52), validado en el spike BANK-51.
-- Las restricciones son la última barrera: aunque el código tuviera un error, la BASE no deja
-- un saldo negativo, una transferencia duplicada ni un movimiento sin cuenta.
-- Montos en NUMERIC(19,2): decimales exactos (nunca float/double para dinero).

-- Cuentas
CREATE TABLE account (
    id           UUID          PRIMARY KEY,
    number       VARCHAR(20)   NOT NULL UNIQUE,
    customer_id  VARCHAR(64)   NOT NULL,
    type         VARCHAR(16)   NOT NULL CHECK (type IN ('AHORROS', 'CORRIENTE')),
    currency     VARCHAR(3)    NOT NULL CHECK (currency IN ('PEN', 'USD')),
    balance      NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    status       VARCHAR(16)   NOT NULL CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    created_at   TIMESTAMPTZ   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL
);
-- "Mis cuentas" busca por cliente
CREATE INDEX idx_account_customer ON account (customer_id);
-- Correlativo del número de cuenta 001-NNNNNNNNNN-D (BANK-53)
CREATE SEQUENCE account_number_seq;

-- Transferencias: la clave de idempotencia es única, así un reintento no transfiere dos veces
CREATE TABLE transfer (
    id               UUID          PRIMARY KEY,
    idempotency_key  UUID          NOT NULL UNIQUE,
    -- Huella de la petición: la misma clave con otros datos es un error, no un reintento
    request_hash     VARCHAR(64)   NOT NULL,
    requested_by     VARCHAR(64)   NOT NULL,
    from_account_id  UUID          NOT NULL REFERENCES account (id),
    to_account_id    UUID          NOT NULL REFERENCES account (id),
    amount           NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency         VARCHAR(3)    NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    CHECK (from_account_id <> to_account_id)
);

-- Movimientos: cada cambio de saldo deja rastro con el saldo resultante
CREATE TABLE movement (
    id             UUID          PRIMARY KEY,
    account_id     UUID          NOT NULL REFERENCES account (id),
    type           VARCHAR(8)    NOT NULL CHECK (type IN ('DEBIT', 'CREDIT')),
    amount         NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    balance_after  NUMERIC(19,2) NOT NULL CHECK (balance_after >= 0),
    -- Vacío en un depósito de ventanilla (BANK-55)
    transfer_id    UUID          REFERENCES transfer (id),
    created_at     TIMESTAMPTZ   NOT NULL
);
-- Historial de una cuenta, del más reciente al más antiguo (BANK-57)
CREATE INDEX idx_movement_account_created ON movement (account_id, created_at DESC);
