#!/usr/bin/env bash
# La prueba de punta a punta en la CI (BANK-96): el sistema completo en contenedores, con el emulador de Cosmos en vez
# del Cosmos real (infra/docker-compose.ci.yml), y Karate contra el gateway. Espera las imágenes ya construidas
# (bank-<servicio>:local). Termina con el resultado de Karate; la CI guarda los logs si falla.
# Uso: tests/e2e/run-ci.sh                      (desde la raíz del repo, con los puertos 8080, 8180, 8025 y 8081 libres)
#      E2E_ACCOUNTS=quarkus tests/e2e/run-ci.sh   la misma prueba con accounts-service-quarkus detrás del gateway
#                                                 (BANK-103); por defecto, spring
set -euo pipefail

# Qué versión de accounts atiende /api/accounts y /api/transfers: los pesos del canary del gateway (BANK-104)
case "${E2E_ACCOUNTS:-spring}" in
  spring) export ACCOUNTS_SPRING_WEIGHT=100 ACCOUNTS_QUARKUS_WEIGHT=0 ;;
  quarkus) export ACCOUNTS_SPRING_WEIGHT=0 ACCOUNTS_QUARKUS_WEIGHT=100 ;;
  *) echo "✗ E2E_ACCOUNTS debe ser spring o quarkus, no ${E2E_ACCOUNTS}" >&2; exit 2 ;;
esac

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE=(docker compose -f "$ROOT/infra/docker-compose.yml" -f "$ROOT/infra/docker-compose.ci.yml" --profile app)
GATEWAY=http://localhost:8080
TOKEN_URL=http://localhost:8180/realms/bank/protocol/openid-connect/token

step() { echo "▶ $1 ($(date +%T))"; }

# Espera hasta que la URL responda el código esperado con el token dado, como máximo $4 segundos
wait_status() {
  local url=$1 token=$2 expected=$3 timeout=$4 waited=0
  until [ "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" "$url")" = "$expected" ]; do
    waited=$((waited + 2))
    if [ "$waited" -ge "$timeout" ]; then
      echo "✗ $url no respondió $expected en $timeout s" >&2
      return 1
    fi
    sleep 2
  done
}

token() {
  curl -s -X POST "$TOKEN_URL" -d grant_type=password -d client_id=bank-cli -d username="$1" -d password="$2" \
    | python3 -I -c "import sys,json; print(json.load(sys.stdin)['access_token'])"
}

step "Emulador de Cosmos"
# El mainframe emulado se construye aquí (es chico); las imágenes de los servicios ya llegan construidas
"${COMPOSE[@]}" build core-banking-mock
"${COMPOSE[@]}" up -d --no-build --wait --wait-timeout 180 cosmos
python3 -I "$ROOT/infra/cosmos-emulator/init.py"

step "Sistema completo (imágenes ya construidas)"
"${COMPOSE[@]}" up -d --no-build --wait --wait-timeout 400

step "Rutas del gateway (accounts: ${E2E_ACCOUNTS:-spring})"
ADMIN=$(token asesor asesor-local)
# Un id que no existe responde 404 desde el servicio: el gateway ya lo encontró en Eureka
wait_status "$GATEWAY/api/customers/no-existe" "$ADMIN" 404 120
wait_status "$GATEWAY/api/accounts/00000000-0000-4000-8000-000000000000" "$ADMIN" 404 120

step "Cuentas de prueba"
ACCOUNTS_URL="$GATEWAY/api" "$ROOT/infra/seed-local.sh" --if-empty

# Precalentamiento: la primera llamada de accounts a customers por gRPC, con el cliente de Cosmos en frío, pasa el
# deadline de 2 s y responde 503 (BANK-95). Se repite hasta que el camino completo responda, como haría una
# startupProbe en AKS; no es la prueba, solo deja el sistema en el estado en que lo usa un cliente
step "Precalentamiento"
ANA=$(token ana ana-local)
wait_status "$GATEWAY/api/customers/me" "$ANA" 200 90
wait_status "$GATEWAY/api/accounts/me" "$ANA" 200 90

step "Karate"
cd "$ROOT/tests/e2e"
./gradlew test --no-daemon
