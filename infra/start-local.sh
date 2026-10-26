#!/usr/bin/env bash
# Levanta el entorno local en orden y espera a que cada pieza esté sana (BANK-49):
#   Docker (Redis, PostgreSQL, Keycloak, Kafka, Mailpit y el mainframe emulado) → config-server → discovery-server → customers-service
#   → accounts-service → notifications-service → api-gateway
# Uso: infra/start-local.sh                los servicios con bootRun (para desarrollar: cambias código y reinicias uno)
#      infra/start-local.sh --containers   los servicios también en contenedores, como en producción (BANK-93)
# Apagar: infra/stop-local.sh [--containers] [--all]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTAINERS=false
[ "${1:-}" = "--containers" ] && CONTAINERS=true
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

# Secreto del cliente notifications-service del Keycloak LOCAL (infra/keycloak/bank-realm.json, BANK-71): no es
# un secreto real, por eso puede ir aquí; en Azure llega por variable desde Key Vault
export NOTIFICATIONS_CLIENT_SECRET="${NOTIFICATIONS_CLIENT_SECRET:-notifications-local}"

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

# Espera hasta que el contenedor $1 muestre el estado $2 (salida del --format $3), como máximo $4 segundos
wait_container() {
  local name=$1 expected=$2 format=$3 timeout=$4 waited=0
  until [ "$(docker inspect -f "$format" "$name" 2>/dev/null)" = "$expected" ]; do
    waited=$((waited + 2))
    [ "$waited" -ge "$timeout" ] && return 1
    sleep 2
  done
}

echo "▶ Docker: Redis, PostgreSQL, Keycloak, Kafka, Mailpit y el mainframe emulado"
# kafka-init vuelve a correr en cada arranque: crea solo los topics que falten (--if-not-exists)
docker compose -f "$ROOT/infra/docker-compose.yml" up -d > /dev/null
if ! wait_for "http://localhost:8180/realms/bank/.well-known/openid-configuration" 120; then
  echo "✗ Keycloak no respondió en :8180" >&2
  exit 1
fi
# PostgreSQL, Kafka y Mailpit están listos cuando su health check pasa a healthy
HEALTH='{{.State.Health.Status}}'
for container in bank-postgres bank-kafka bank-mailpit bank-core-banking-mock; do
  if ! wait_container "$container" healthy "$HEALTH" 90; then
    echo "✗ $container no quedó sano: docker logs $container" >&2
    exit 1
  fi
done
# Sin topics los servicios no pueden publicar ni consumir: kafka-init tiene que terminar bien (exit 0)
if ! wait_container bank-kafka-init "exited 0" '{{.State.Status}} {{.State.ExitCode}}' 60; then
  echo "✗ kafka-init no creó los topics: docker logs bank-kafka-init" >&2
  exit 1
fi
echo "• redis :6380, postgres :5433, keycloak :8180, kafka :9094 (topics listos), mailpit :8025 y core-banking-mock :8089 listos"

if $CONTAINERS; then
  # Los dos modos usan los mismos puertos (8080, 8761): no se mezclan
  if pgrep -f 'bootRun|ServiceApplication|ServerApplication|ApiGatewayApplication' > /dev/null; then
    echo "✗ Hay servicios corriendo con bootRun: apágalos con infra/stop-local.sh antes de usar --containers" >&2
    exit 1
  fi
  echo "▶ Servicios en contenedores (construye las imágenes que falten o hayan cambiado)"
  # --wait: vuelve cuando los 6 están healthy (su HEALTHCHECK), en el orden de los depends_on
  if ! docker compose -f "$ROOT/infra/docker-compose.yml" --profile app up -d --build --wait --wait-timeout 300 > "$LOGS/containers.log" 2>&1; then
    echo "✗ algún servicio no quedó sano. Revisa $LOGS/containers.log y docker compose -f infra/docker-compose.yml --profile app ps" >&2
    exit 1
  fi
  echo "• config-server, discovery-server, customers-service, accounts-service, notifications-service y api-gateway healthy"
  GATEWAY_LOG="docker logs bank-api-gateway"
  # accounts no se publica a la Mac: el seed abre las cuentas por el gateway
  export ACCOUNTS_URL=http://localhost:8080/api
else
  echo "▶ Servicios"
  start config-server 8888 /actuator/health
  start discovery-server 8761 /actuator/health
  start customers-service 8081 /actuator/health/liveness
  start accounts-service 8082 /actuator/health/readiness
  start notifications-service 8083 /actuator/health/readiness
  start api-gateway 8080 /actuator/health/liveness
  GATEWAY_LOG="$LOGS/api-gateway.log"
fi

# El gateway enruta cuando ya tiene la lista de Eureka (~20-30 s): se prueba con el asesor local
# (usuario de prueba del realm) hasta que un id inexistente responda 404 desde el servicio
TOKEN=$(curl -s -X POST http://localhost:8180/realms/bank/protocol/openid-connect/token \
  -d grant_type=password -d client_id=bank-cli -d username=asesor -d password=asesor-local \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")
wait_route() {
  local name=$1 path=$2 waited=0
  printf "• gateway → %-10s … " "$name"
  until [ "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
            "http://localhost:8080$path")" = "404" ]; do
    waited=$((waited + 2))
    if [ "$waited" -ge 90 ]; then
      echo "✗ el gateway no enruta a $name; revisa $GATEWAY_LOG"
      exit 1
    fi
    sleep 2
  done
  echo "enrutando"
}
wait_route customers /api/customers/no-existe
wait_route accounts /api/accounts/00000000-0000-4000-8000-000000000000

# Con la base vacía (primer arranque o después de --all), las cuentas de prueba con números y saldos fijos (BANK-75)
"$ROOT/infra/seed-local.sh" --if-empty

if $CONTAINERS; then
  SWAGGER="no se publica en este modo (usa la API por el gateway)"
  CONFIG="no se publica en este modo: docker exec bank-config-server curl -s localhost:8888/customers-service/default"
  LOGS_HINT="docker logs bank-<servicio>"
  STOP="infra/stop-local.sh --containers   (con --all también la infraestructura)"
else
  SWAGGER="http://localhost:8081/swagger-ui.html  ·  http://localhost:8082/swagger-ui.html"
  CONFIG="http://localhost:8888/customers-service/default"
  LOGS_HINT="$LOGS"
  STOP="infra/stop-local.sh   (con --all también los contenedores de Docker)"
fi

cat <<EOF

✅ Entorno local listo
   API (gateway)   http://localhost:8080/api/customers/...  /api/accounts/...  /api/transfers
   Swagger         $SWAGGER
   Avisos          notifications-service :8083 (sin API; consume bank.transfers.v1)
   Eureka          http://localhost:8761
   Config          $CONFIG
   Keycloak        http://localhost:8180/admin  (admin / admin)
   Kafka           localhost:9094  (topics bank.transfers.v1 y bank.transfers.v1.DLT)
   Correos         http://localhost:8025  (Mailpit)
   Mainframe       http://localhost:8089/core/asientos  (core-banking-mock, tramas de largo fijo)
   Datos de prueba infra/seed-local.sh --show  (número → id de cada cuenta)
   Logs            $LOGS_HINT
   Apagar          $STOP
EOF
