#!/usr/bin/env bash
# Las respuestas de error de las dos versiones de accounts, campo por campo (BANK-103). Manda la MISMA petición a
# accounts-service (Spring, :8082) y a accounts-service-quarkus (:8084), cada una desde dentro de su contenedor (el de
# Quarkus no publica puerto), con tokens reales del Keycloak local, y compara código HTTP y ProblemDetail: todos los
# campos, con "errors" sin importar el orden. Ninguna petición mueve dinero salvo la preparación del 409 (un depósito
# de 0.01 en la cuenta USD de Ana, una sola vez por corrida).
# Uso: tests/parity/compare-errors.sh            (modo contenedores: start-local.sh --containers)
#      tests/parity/compare-errors.sh --503      solo el caso 503, con customers-service detenido a propósito
set -euo pipefail

TOKEN_URL=http://localhost:8180/realms/bank/protocol/openid-connect/token
# Cuentas del seed local (infra/seed-local.sh): Ana PEN y USD, la de Luis (destino de la E2E)
ANA_PEN=08ed4945-37fd-4b8c-a989-3f9ba67b7d73
ANA_USD=38bcc244-a879-49dc-8663-da93832f2fa4
LUIS_PEN=a8b8a872-ddd3-4c86-a63b-892cba75221c
MISSING=00000000-0000-4000-8000-000000000000

token() {
  curl -s -X POST "$TOKEN_URL" -d grant_type=password -d client_id=bank-cli -d username="$1" -d password="$2" \
    | python3 -I -c "import sys,json; print(json.load(sys.stdin)['access_token'])"
}
ANA=$(token ana ana-local)
ADMIN=$(token asesor asesor-local)

# call <contenedor> <puerto> <token|-> <método> <ruta> [clave] [cuerpo] → "código\tcuerpo"
call() {
  local args=(-s -o /dev/stdout -w '\t%{http_code}' -X "$4")
  [ "$3" != "-" ] && args+=(-H "Authorization: Bearer $3")
  [ -n "${6:-}" ] && args+=(-H "Idempotency-Key: $6")
  [ -n "${7:-}" ] && args+=(-H "Content-Type: application/json" --data-raw "$7")
  docker exec "$1" curl "${args[@]}" "http://localhost:$2$5"
}

results=$(mktemp)
trap 'rm -f "$results"' EXIT

# case <nombre> <token|-> <método> <ruta> [clave] [cuerpo]: la misma petición a las dos versiones
case_() {
  local name=$1; shift
  printf '%s\x1f%s\x1f%s\n' "$name" "$(call bank-accounts-service 8082 "$@")" \
    "$(call bank-accounts-service-quarkus 8084 "$@")" >> "$results"
}

transfer() { echo "{\"fromAccountId\":\"$1\",\"toAccountNumber\":\"$2\",\"amount\":\"$3\"}"; }

if [ "${1:-}" = "--503" ]; then
  case_ "503 customers-service caído" "$ANA" GET /accounts/me
else
  case_ "400 id con formato inválido" "$ADMIN" GET /accounts/no-es-un-uuid
  case_ "400 campo inválido (monto 0)" "$ANA" POST /transfers "$(uuidgen)" "$(transfer $ANA_PEN 001-0000000003-2 0)"
  case_ "400 sin Idempotency-Key" "$ANA" POST /transfers "" "$(transfer $ANA_PEN 001-0000000003-2 1.00)"
  case_ "400 JSON mal formado" "$ANA" POST /transfers "$(uuidgen)" '{"fromAccountId":'
  case_ "400 misma cuenta" "$ANA" POST /transfers "$(uuidgen)" "$(transfer $ANA_PEN 001-0000000001-6 1.00)"
  case_ "401 sin token" - GET /accounts/me
  case_ "401 token inválido" "no.es.un-token" GET /accounts/me
  case_ "403 cuenta ajena" "$ANA" GET "/accounts/$LUIS_PEN"
  case_ "403 transferir desde cuenta ajena" "$ANA" POST /transfers "$(uuidgen)" "$(transfer $LUIS_PEN 001-0000000001-6 1.00)"
  case_ "404 cuenta inexistente (asesor)" "$ADMIN" GET "/accounts/$MISSING"
  case_ "404 transferencia inexistente (asesor)" "$ADMIN" GET "/transfers/$MISSING"
  # 409: la clave se usa una vez (por Spring) y las dos versiones la reciben después con otro monto
  KEY=$(uuidgen)
  call bank-accounts-service 8082 "$ADMIN" POST "/accounts/$ANA_USD/deposits" "$KEY" '{"amount":"0.01"}' > /dev/null
  case_ "409 Idempotency-Key con otros datos" "$ADMIN" POST "/accounts/$ANA_USD/deposits" "$KEY" '{"amount":"0.02"}'
  case_ "422 fondos insuficientes" "$ANA" POST /transfers "$(uuidgen)" "$(transfer $ANA_PEN 001-0000000003-2 999999.00)"
  case_ "422 monedas distintas" "$ANA" POST /transfers "$(uuidgen)" "$(transfer $ANA_PEN 001-0000000002-4 1.00)"
fi

python3 -I - "$results" <<'PY'
import json, sys
def parse(raw):
    body, _, code = raw.rpartition("\t")
    try:
        data = json.loads(body) if body.strip() else None
    except json.JSONDecodeError:
        data = {"<no es JSON>": body[:80]}
    if isinstance(data, dict) and isinstance(data.get("errors"), list):
        data["errors"] = sorted(data["errors"], key=lambda e: json.dumps(e, sort_keys=True))
    return code, data
different = 0
print("| Caso | Spring | Quarkus | ProblemDetail |\n|---|---|---|---|")
for line in open(sys.argv[1], encoding="utf-8"):
    name, spring, quarkus = line.rstrip("\n").split("\x1f")
    (cs, ds), (cq, dq) = parse(spring), parse(quarkus)
    if cs == cq and ds == dq:
        verdict = f"igual ({', '.join(sorted(ds))})" if isinstance(ds, dict) else "igual (sin cuerpo)"
    else:
        different += 1
        keys = sorted(set(ds or {}) | set(dq or {}))
        diff = [k for k in keys if (ds or {}).get(k) != (dq or {}).get(k)]
        verdict = f"**distinto** en {', '.join(diff) or 'el código'}: Spring={json.dumps({k: (ds or {}).get(k) for k in diff}, ensure_ascii=False)} Quarkus={json.dumps({k: (dq or {}).get(k) for k in diff}, ensure_ascii=False)}"
    print(f"| {name} | {cs} | {cq} | {verdict} |")
print(f"\n{'Todas iguales' if not different else f'{different} distintas'}")
sys.exit(1 if different else 0)
PY
