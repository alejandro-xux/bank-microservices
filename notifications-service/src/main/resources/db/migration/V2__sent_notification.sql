-- Un aviso por destinatario (BANK-72). Un evento puede generar dos correos (al que envía y al que recibe): si el
-- segundo falla, el reintento no debe repetir el primero, así que el "ya lo mandé" va por (evento, destinatario).
-- El INSERT se hace en la misma transacción que el envío: si el envío falla hay ROLLBACK y el reclamo se libera;
-- si otro consumidor procesa el mismo evento a la vez, espera en la clave primaria y después no manda nada
CREATE TABLE sent_notification (
    event_id    UUID         NOT NULL,
    -- SENDER (envió), RECEIVER (recibió) o SELF (entre sus propias cuentas)
    recipient   VARCHAR(10)  NOT NULL,
    customer_id VARCHAR(100) NOT NULL,
    -- false: no había a quién mandarlo (cliente inexistente o sin correo); no se reintenta
    sent        BOOLEAN      NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, recipient)
);
