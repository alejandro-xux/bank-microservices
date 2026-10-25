#!/bin/bash
# Topics del sistema (BANK-67, spike BANK-66 K2). Los crea la infraestructura, nunca la app:
# en local este script (contenedor kafka-init); en Azure, Terraform/Bicep crea los event hubs.
# --if-not-exists: se puede correr en cada arranque sin duplicar ni fallar
set -euo pipefail

BOOTSTRAP=${BOOTSTRAP:-kafka:9092}
TOPICS=/opt/kafka/bin/kafka-topics.sh

create() {
  local topic=$1 partitions=$2
  "$TOPICS" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$topic" --partitions "$partitions" --replication-factor 1
}

# Transferencias completadas (accounts-service → notifications-service). Clave = cuenta origen:
# los eventos de una cuenta caen siempre en la misma partición y conservan su orden
create bank.transfers.v1 3
# Mensajes que nunca se van a poder procesar (BANK-73). Mismas particiones: el DLT conserva la del original
create bank.transfers.v1.DLT 3

"$TOPICS" --bootstrap-server "$BOOTSTRAP" --describe --topic 'bank\..*' | grep PartitionCount
