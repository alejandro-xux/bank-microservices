-- Una sola instancia de PostgreSQL con una base y un usuario por servicio (BANK-52), como un
-- Flexible Server en Azure con varias bases. Cada usuario es dueño solo de su base: un servicio
-- no puede leer las tablas de otro. Se ejecuta solo la primera vez que arranca el contenedor.
-- Contraseñas solo para el entorno local (no son secretos); en Azure llegan por variables.

CREATE ROLE accounts LOGIN PASSWORD 'accounts-local';
CREATE DATABASE accounts OWNER accounts;
REVOKE ALL ON DATABASE accounts FROM PUBLIC;

-- notifications-service (BANK-70): registra qué eventos ya procesó, para no avisar dos veces
CREATE ROLE notifications LOGIN PASSWORD 'notifications-local';
CREATE DATABASE notifications OWNER notifications;
REVOKE ALL ON DATABASE notifications FROM PUBLIC;
