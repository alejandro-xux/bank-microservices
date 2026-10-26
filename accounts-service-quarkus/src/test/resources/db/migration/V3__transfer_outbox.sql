-- Transactional Outbox (BANK-69, spike BANK-66 K3). Cada transferencia guarda aquí su evento en la MISMA
-- transacción que mueve el dinero: si hay ROLLBACK no queda evento; si hay COMMIT, el evento existe sí o sí.
-- Un relay (OutboxRelay) lo publica después en Kafka y lo marca como publicado.
CREATE TABLE outbox (
    -- event_id del evento: con él el consumidor descarta los duplicados
    id           UUID         PRIMARY KEY,
    -- Orden de publicación. No sirve created_at: now() es la hora en que EMPEZÓ la transacción, y una que
    -- empezó antes pero esperó el candado de la cuenta quedaría "antes" que la que la hizo esperar.
    -- La secuencia se toma al insertar, ya con los candados de las cuentas, así que dos eventos de una
    -- misma cuenta siempre quedan en el orden en que se movió el dinero
    seq          BIGINT       GENERATED ALWAYS AS IDENTITY UNIQUE,
    event_type   VARCHAR(100) NOT NULL,
    -- Clave del mensaje en Kafka (la cuenta origen): sus eventos van a la misma partición, en orden
    message_key  VARCHAR(100) NOT NULL,
    -- El evento en Protobuf (contracts/transfer_events.proto), tal como viaja
    payload      BYTEA        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ
);

-- Solo las pendientes: el relay las lee en orden y el índice se queda chico aunque la tabla crezca
CREATE INDEX outbox_pending ON outbox (seq) WHERE published_at IS NULL;
