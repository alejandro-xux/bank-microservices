-- Esquema de notifications-service (BANK-70), su propia base (database per service).
-- Eventos ya procesados: Kafka entrega "al menos una vez" (spike BANK-66, K7), así que el mismo evento puede
-- llegar dos veces; esta tabla es la que permite reconocerlo y no avisar de nuevo (la usa BANK-72)
CREATE TABLE processed_event (
    event_id     UUID         PRIMARY KEY,
    event_type   VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
