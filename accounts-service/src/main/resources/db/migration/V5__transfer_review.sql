-- Recuperador de la SAGA (BANK-79). Una transferencia que sigue PENDING después del tope de antigüedad (el core no
-- responde ni a la consulta) queda marcada para que alguien del banco la revise con el core. NO se compensa a ciegas:
-- si el core sí registró el asiento, devolver el dinero lo dejaría en dos lugares (spike BANK-74, P5)
ALTER TABLE transfer ADD COLUMN needs_review BOOLEAN NOT NULL DEFAULT false;
