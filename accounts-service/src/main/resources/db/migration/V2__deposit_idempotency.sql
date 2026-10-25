-- Idempotencia de los depósitos en ventanilla (BANK-64). En un depósito, el registro que reclama la
-- Idempotency-Key es el propio movimiento (no hay tabla transfer): la base impide registrar dos veces
-- el mismo depósito aunque lleguen dos peticiones a la vez.
-- Las dos columnas son opcionales: los depósitos ya registrados y los movimientos de las transferencias
-- (su clave vive en transfer) no tienen. UNIQUE en PostgreSQL admite varios NULL
ALTER TABLE movement ADD COLUMN idempotency_key UUID UNIQUE;

-- Huella de la petición (cuenta y monto): la misma clave con otros datos es un error (409), no un reintento
ALTER TABLE movement ADD COLUMN request_hash VARCHAR(64);

-- Una clave siempre viene con su huella
ALTER TABLE movement ADD CONSTRAINT movement_idempotency_has_hash
    CHECK ((idempotency_key IS NULL) = (request_hash IS NULL));
