#!/usr/bin/env bash
# Apaga los servicios del entorno local en orden inverso (BANK-49); con --all también Redis y Keycloak.
# Uso: infra/stop-local.sh [--all]
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Primero la puerta de entrada (gateway), al final la infraestructura base
for app in ApiGatewayApplication CustomersServiceApplication DiscoveryServerApplication ConfigServerApplication; do
  if pkill -f "$app"; then
    echo "• $app detenido"
  fi
done

# Espera a que los puertos queden libres (máx. 30 s)
for port in 8080 8081 8761 8888; do
  waited=0
  while curl -s -o /dev/null "http://localhost:$port" && [ "$waited" -lt 30 ]; do
    sleep 1
    waited=$((waited + 1))
  done
done

if [ "${1:-}" = "--all" ]; then
  docker compose -f "$ROOT/infra/docker-compose.yml" down
  echo "• Redis y Keycloak detenidos"
fi
echo "✅ Entorno local apagado"
