#!/usr/bin/env bash
# Datos de prueba locales (BANK-75): las cuentas con las que probamos, con números y saldos fijos.
# Se crean por la API, como lo haría un asesor (abrir cuenta + depósito con Idempotency-Key), así pasan por las
# mismas reglas que cualquier cuenta. Idempotente: si ya están, no crea ni deposita nada.
# Uso: infra/seed-local.sh            crea lo que falte (o avisa si la base tiene otras cuentas)
#      infra/seed-local.sh --if-empty  solo si accounts no tiene ninguna cuenta (lo usa start-local.sh)
#      infra/seed-local.sh --show      lista número → id (los ids cambian cada vez que la base empieza de cero)
set -euo pipefail

ACCOUNTS=${ACCOUNTS_URL:-http://localhost:8082}
TOKEN_URL=${TOKEN_URL:-http://localhost:8180/realms/bank/protocol/openid-connect/token}

# Clientes de prueba (sus fichas viven en Cosmos y no se tocan): Ana y el cliente de la cuenta destino
ANA=c15a442d-5146-471e-8804-746f4b42253b
DESTINO=08d38a3b-c398-48fb-be36-3d6a1dde7ad2

# número esperado | cliente | moneda | saldo inicial. El orden importa: en una base vacía la secuencia de
# números empieza en 1, así que abrirlas en este orden les da exactamente estos números
SEED=(
  "001-0000000001-6|$ANA|PEN|1580.50"
  "001-0000000002-4|$ANA|USD|250.00"
  "001-0000000003-2|$DESTINO|PEN|370.00"
  "001-0000000004-0|$ANA|USD|0.00"
)

psql_accounts() {
  docker exec bank-postgres psql -U accounts -d accounts -tAc "$1"
}

show() {
  echo "Cuentas de prueba (número → id):"
  psql_accounts "select number || '  ' || id || '  ' || currency || ' ' || balance from account order by number" | sed 's/^/  /'
}

if [ "${1:-}" = "--show" ]; then
  show
  exit 0
fi

total=$(psql_accounts "select count(*) from account")
if [ "${1:-}" = "--if-empty" ] && [ "$total" != "0" ]; then
  echo "• datos de prueba: la base ya tiene $total cuentas, no se toca"
  exit 0
fi

# Cuáles de las esperadas ya existen
numbers=$(printf "'%s'," "${SEED[@]%%|*}"); numbers=${numbers%,}
present=$(psql_accounts "select count(*) from account where number in ($numbers)")
if [ "$present" = "${#SEED[@]}" ]; then
  echo "• datos de prueba: las ${#SEED[@]} cuentas ya existen, no se toca nada"
  exit 0
fi
if [ "$present" != "0" ] || [ "$total" != "0" ]; then
  # Con otras cuentas en la base, la secuencia ya avanzó y los números no saldrían iguales: mejor no adivinar
  echo "✗ La base tiene $total cuentas y solo $present de las de prueba. Los números no saldrían iguales;" >&2
  echo "  para volver a los datos de prueba: infra/stop-local.sh --all && infra/start-local.sh (borra todo lo local)" >&2
  exit 1
fi

# Token del asesor local (usuario de prueba del realm, solo local)
TOKEN=$(curl -s -X POST "$TOKEN_URL" -d grant_type=password -d client_id=bank-cli \
  -d username=asesor -d password=asesor-local | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

for row in "${SEED[@]}"; do
  IFS='|' read -r number customer currency balance <<< "$row"
  opened=$(curl -s -X POST "$ACCOUNTS/accounts" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"customerId\":\"$customer\",\"type\":\"AHORROS\",\"currency\":\"$currency\"}")
  read -r id got < <(python3 -c "import sys,json; d=json.loads(sys.argv[1]); print(d['id'], d['number'])" "$opened")
  if [ "$got" != "$number" ]; then
    echo "✗ Se esperaba la cuenta $number y se abrió $got: revisa la base" >&2
    exit 1
  fi
  if [ "$balance" != "0.00" ]; then
    curl -s -o /dev/null -X POST "$ACCOUNTS/accounts/$id/deposits" -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d "{\"amount\":\"$balance\"}"
  fi
  printf "• %s %s %8s  %s\n" "$number" "$currency" "$balance" "$id"
done
echo "✅ Datos de prueba creados"
