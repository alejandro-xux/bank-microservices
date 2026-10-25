#!/usr/bin/env bash
# Levanta el entorno local en orden y espera a que cada pieza esté sana (BANK-49):
#   Docker (Redis, PostgreSQL y Keycloak) → config-server → discovery-server → customers-service
#   → accounts-service → api-gateway
# Uso: infra/start-local.sh          Apagar: infra/stop-local.sh [--all]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$ROOT/.local/logs"
ENV_FILE="${BANK_ENV_FILE:-$HOME/.bank-microservices.env}"
mkdir -p "$LOGS"

# Credenciales de Cosmos (las usa customers-service): solo desde el entorno, nunca en el repo
if [ -f "$ENV_FILE" ]; then
  set -a; . "$ENV_FILE"; set +a
fi
if [ -z "${COSMOS_ENDPOINT:-}" ] || [ -z "${COSMOS_KEY:-}" ]; then
  echo "✗ Faltan COSMOS_ENDPOINT y COSMOS_KEY: defínelas o créalas en $ENV_FILE" >&2
  exit 1
fi

# Espera hasta que la URL responda con el código esperado (por defecto 200), como máximo $2 segundos
wait_for() {
  local url=$1 timeout=$2 expected=${3:-200} waited=0
  until [ "$(curl -s -o /dev/null -w '%{http_code}' "$url")" = "$expected" ]; do
    waited=$((waited + 2))
    [ "$waited" -ge "$timeout" ] && return 1
    sleep 2
  done
}

# Arranca un servicio con bootRun (si su puerto no responde) y espera su health
start() {
  local name=$1 port=$2 health="http://localhost:$2$3"
  if curl -s -o /dev/null "http://localhost:$port"; then
    echo "• $name ya estaba corriendo en :$port"
    return
  fi
  printf "• %-18s :%s … " "$name" "$port"
  (cd "$ROOT/$name" && nohup ./gradlew bootRun -q > "$LOGS/$name.log" 2>&1 &)
  if wait_for "$health" 180; then
    echo "UP"
  else
    echo "✗ no arrancó. Últimas líneas de $LOGS/$name.log:"
    tail -20 "$LOGS/$name.log"
    exit 1
  fi
}

echo "▶ Docker: Redis, PostgreSQL y Keycloak"
docker compose -f "$ROOT/infra/docker-compose.yml" up -d > /dev/null
if ! wait_for "http://localhost:8180/realms/bank/.well-known/openid-configuration" 120; then
  echo "✗ Keycloak no respondió en :8180" >&2
  exit 1
fi
# PostgreSQL está listo cuando su health check (pg_isready) pasa a healthy
waited=0
until [ "$(docker inspect -f '{{.State.Health.Status}}' bank-postgres)" = "healthy" ]; do
  waited=$((waited + 2))
  if [ "$waited" -ge 60 ]; then
    echo "✗ PostgreSQL no respondió en :5433" >&2
    exit 1
  fi
  sleep 2
done
echo "• redis :6380, postgres :5433 y keycloak :8180 listos"

echo "▶ Servicios"
start config-server 8888 /actuator/health
start discovery-server 8761 /actuator/health
start customers-service 8081 /actuator/health/liveness
start accounts-service 8082 /actuator/health/readiness
start api-gateway 8080 /actuator/health/liveness

# El gateway enruta cuando ya tiene la lista de Eureka (~20-30 s): se prueba con el asesor local
# (usuario de prueba del realm) hasta que un id inexistente responda 404 desde customers-service
printf "• gateway → customers  … "
TOKEN=$(curl -s -X POST http://localhost:8180/realms/bank/protocol/openid-connect/token \
  -d grant_type=password -d client_id=bank-cli -d username=asesor -d password=asesor-local \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")
waited=0
until [ "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
          http://localhost:8080/api/customers/no-existe)" = "404" ]; do
  waited=$((waited + 2))
  if [ "$waited" -ge 90 ]; then
    echo "✗ el gateway no enruta a customers-service; revisa $LOGS/api-gateway.log"
    exit 1
  fi
  sleep 2
done
echo "enrutando"

cat <<EOF

✅ Entorno local listo
   API (gateway)   http://localhost:8080/api/customers/...
   Swagger         http://localhost:8081/swagger-ui.html
   Accounts        http://localhost:8082/actuator/health
   Eureka          http://localhost:8761
   Config          http://localhost:8888/customers-service/default
   Keycloak        http://localhost:8180/admin  (admin / admin)
   Logs            $LOGS
   Apagar          infra/stop-local.sh   (con --all también Redis, PostgreSQL y Keycloak)
EOF
