#!/usr/bin/env bash
# Construye las imágenes de los 6 servicios (BANK-92) y revisa cada una: usuario sin privilegios, HEALTHCHECK y
# ningún secreto en sus capas ni en su configuración. Termina con error si alguna revisión falla.
# Uso: infra/build-images.sh                    construye todas y las revisa
#      infra/build-images.sh accounts-service   solo esa
#      infra/build-images.sh --check-only       revisa las que ya están construidas
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVICES=(config-server discovery-server customers-service accounts-service notifications-service api-gateway)
UID_EXPECTED=10001
# Nombres que no pueden aparecer con un valor dentro de una imagen: las credenciales llegan al correr, por variables
SECRET_PATTERN='COSMOS_KEY|COSMOS_ENDPOINT|CLIENT_SECRET|PASSWORD|SONAR_TOKEN|AccountKey='

build=true
selected=()
for arg in "$@"; do
  case "$arg" in
    --check-only) build=false ;;
    *) selected+=("$arg") ;;
  esac
done
[ ${#selected[@]} -eq 0 ] && selected=("${SERVICES[@]}")

failures=0
fail() { echo "  ✗ $1"; failures=$((failures + 1)); }

for service in "${selected[@]}"; do
  image="bank-$service:local"
  if $build; then
    started=$(date +%s)
    printf "• %-22s construyendo … " "$service"
    if ! docker build -q -f "$ROOT/$service/Dockerfile" -t "$image" "$ROOT" > /dev/null; then
      echo "✗ falló la construcción"; exit 1
    fi
    echo "$(( $(date +%s) - started )) s"
  fi
  echo "  $image"

  # 1. La imagen declara un usuario sin privilegios y el proceso corre de verdad con él
  user=$(docker image inspect -f '{{.Config.User}}' "$image")
  [ "$user" = "$UID_EXPECTED" ] && echo "  ✓ USER $user" || fail "USER es '${user:-root}', se esperaba $UID_EXPECTED"
  runtime_uid=$(docker run --rm --entrypoint id "$image" -u)
  [ "$runtime_uid" = "$UID_EXPECTED" ] && echo "  ✓ corre con uid $runtime_uid" || fail "corre con uid $runtime_uid"

  # 2. Tiene HEALTHCHECK
  health=$(docker image inspect -f '{{if .Config.Healthcheck}}{{join .Config.Healthcheck.Test " "}}{{end}}' "$image")
  [ -n "$health" ] && echo "  ✓ HEALTHCHECK" || fail "no tiene HEALTHCHECK"

  # 3. Ningún secreto en las variables de la imagen ni en los comandos de sus capas
  if docker image inspect -f '{{join .Config.Env "\n"}}' "$image" | grep -qE "$SECRET_PATTERN" \
     || docker history --no-trunc --format '{{.CreatedBy}}' "$image" | grep -qE "$SECRET_PATTERN"; then
    fail "aparece una credencial en Env o en el historial de capas"
  else
    echo "  ✓ sin credenciales en Env ni en las capas"
  fi

  # Tamaño comprimido, el que se sube a un registro
  size=$(docker save "$image" | gzip -1 | wc -c)
  echo "  ≈ $((size / 1000000)) MB comprimida"
done

if [ "$failures" -gt 0 ]; then
  echo "✗ $failures revisiones fallaron"
  exit 1
fi
echo "✅ Imágenes revisadas"
