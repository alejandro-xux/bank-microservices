#!/usr/bin/env bash
# Apaga los servicios del entorno local en orden inverso (BANK-49); con --all también los contenedores de Docker
# (Redis, PostgreSQL, Keycloak, Kafka, Mailpit y el mainframe emulado; sin volúmenes: la próxima vez arrancan vacíos).
# Uso: infra/stop-local.sh               los servicios de bootRun
#      infra/stop-local.sh --containers  también los servicios en contenedores (start-local.sh --containers, BANK-93)
#      infra/stop-local.sh --all         todo, también la infraestructura
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE=(docker compose -f "$ROOT/infra/docker-compose.yml" --profile app --profile native)
ALL=false
CONTAINERS=false
for arg in "$@"; do
  case "$arg" in
    --all) ALL=true ;;
    --containers) CONTAINERS=true ;;
  esac
done

# Primero la puerta de entrada (gateway), al final la infraestructura base
for app in ApiGatewayApplication NotificationsServiceApplication AccountsServiceApplication CustomersServiceApplication DiscoveryServerApplication ConfigServerApplication; do
  if pkill -f "$app"; then
    echo "• $app detenido"
  fi
done

# Espera a que los puertos queden libres (máx. 30 s)
for port in 8080 8083 8082 8081 8761 8888; do
  waited=0
  while curl -s -o /dev/null "http://localhost:$port" && [ "$waited" -lt 30 ]; do
    sleep 1
    waited=$((waited + 1))
  done
done

if $ALL; then
  # Con los perfiles app y native, down también se lleva los servicios en contenedores (sin él los dejaría corriendo)
  "${COMPOSE[@]}" down > /dev/null 2>&1
  echo "• servicios en contenedores, Redis, PostgreSQL, Keycloak, Kafka, Mailpit y el mainframe emulado detenidos"
elif $CONTAINERS; then
  # Solo los 8 servicios (con accounts-service-quarkus, BANK-104, y fraud-service, BANK-107): la infraestructura (y
  # sus datos) sigue corriendo
  "${COMPOSE[@]}" rm --stop --force api-gateway fraud-service notifications-service accounts-service-quarkus accounts-service \
    customers-service discovery-server config-server > /dev/null 2>&1
  echo "• servicios en contenedores detenidos (la infraestructura sigue corriendo)"
fi
echo "✅ Entorno local apagado"
