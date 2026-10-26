-- SAGA de la transferencia con el core bancario (BANK-78, spike BANK-74). Una transferencia ya no termina en una sola
-- transacción: T1 debita y la deja PENDING, el core registra el asiento, y T2 la completa (crédito) o la compensa
-- (REVERSAL). Las transferencias hechas antes de esta migración se movieron enteras: quedan COMPLETED.
ALTER TABLE transfer ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'COMPLETED'
    CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'));
ALTER TABLE transfer ALTER COLUMN status DROP DEFAULT;

-- Por qué falló (el código del core, por ejemplo 057) y cuándo se resolvió
ALTER TABLE transfer ADD COLUMN failure_reason VARCHAR(30);
ALTER TABLE transfer ADD COLUMN resolved_at TIMESTAMPTZ;

-- El número con el que el core reconoce el asiento (id-op, 12 dígitos): el dominio usa el UUID, el core este
-- número, y el Anti-Corruption Layer traduce (S5). Las transferencias anteriores no pasaron por el core: NULL
CREATE SEQUENCE core_operation_seq START 1 MAXVALUE 999999999999;
ALTER TABLE transfer ADD COLUMN core_operation BIGINT UNIQUE;

-- Las pendientes, para el recuperador (BANK-79): el índice solo guarda esas, así se queda chico
CREATE INDEX transfer_pending ON transfer (created_at) WHERE status = 'PENDING';

-- La compensación es un movimiento más: el débito no se borra, se le suma su reversa (S3)
ALTER TABLE movement DROP CONSTRAINT movement_type_check;
ALTER TABLE movement ADD CONSTRAINT movement_type_check CHECK (type IN ('DEBIT', 'CREDIT', 'REVERSAL'));
