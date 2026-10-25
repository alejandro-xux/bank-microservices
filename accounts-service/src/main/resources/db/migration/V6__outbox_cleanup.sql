-- Limpieza del outbox (BANK-81): las filas publicadas ya no le sirven al relay, solo a quien investigue un evento.
-- Se borran pasados unos días. Este índice tiene solo las publicadas, ordenadas por cuándo se publicaron: la
-- limpieza encuentra las viejas sin recorrer la tabla, y el índice de las pendientes (outbox_pending) sigue chico
CREATE INDEX outbox_published ON outbox (published_at) WHERE published_at IS NOT NULL;
