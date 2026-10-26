#!/usr/bin/env bash
# Revisa con Trivy las imágenes de los servicios (BANK-95, decisión D7): bloquea con cualquier CRITICAL o HIGH que ya
# tenga arreglo y no esté aceptado en .trivyignore; el resto se muestra pero no bloquea. Imprime una tabla en Markdown
# (la CI la pone en el resumen del PR).
# Uso: infra/scan-images.sh                      las 6 imágenes bank-<servicio>:local
#      infra/scan-images.sh bank-accounts-service:local
# En la CI se usa el binario de Trivy (TRIVY_BIN=/ruta/trivy); en la Mac, su imagen de Docker
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TRIVY_IMAGE=aquasec/trivy:0.75.0
images=("$@")
[ ${#images[@]} -eq 0 ] && images=(bank-config-server:local bank-discovery-server:local bank-customers-service:local
  bank-accounts-service:local bank-notifications-service:local bank-api-gateway:local)

reports=$(mktemp -d)
trap 'rm -rf "$reports"' EXIT

trivy() {
  if [ -n "${TRIVY_BIN:-}" ]; then
    "$TRIVY_BIN" "$@"
  else
    docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v bank-trivy-cache:/root/.cache \
      -v "$ROOT/.trivyignore:/.trivyignore:ro" "$TRIVY_IMAGE" "$@"
  fi
}
ignorefile() { if [ -n "${TRIVY_BIN:-}" ]; then echo "$ROOT/.trivyignore"; else echo /.trivyignore; fi; }

# Las bases de vulnerabilidades se bajan primero, probando cada espejo. Trivy cambia de espejo solo si el primero falla
# antes de empezar; si se corta a mitad de la descarga (pasó en la CI con mirror.gcr.io: 404 en una capa), falla sin
# probar el otro. Así un espejo caído no tumba la CI, y después se revisa sin volver a bajar nada
download() {
  local kind=$1; shift
  for repository in "$@"; do
    if trivy image --quiet "--download-$kind-only" "--$kind-repository" "$repository" > /dev/null 2>&1; then
      return 0
    fi
    echo "• no se pudo bajar la base $kind de $repository; pruebo el siguiente espejo" >&2
  done
  echo "✗ no se pudo bajar la base $kind de ningún espejo" >&2
  exit 1
}
download db mirror.gcr.io/aquasec/trivy-db:2 ghcr.io/aquasecurity/trivy-db:2
download java-db mirror.gcr.io/aquasec/trivy-java-db:1 ghcr.io/aquasecurity/trivy-java-db:1

for image in "${images[@]}"; do
  trivy image --quiet --skip-db-update --skip-java-db-update --format json --ignorefile "$(ignorefile)" "$image" \
    > "$reports/$(echo "$image" | tr '/:' '__').json"
done

python3 -I - "$reports" <<'EOF'
import json, pathlib, sys

order = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN"]
blocking = []
print("| Imagen | " + " | ".join(order) + " | Bloquean |")
print("|---|" + "---|" * (len(order) + 1))
for report in sorted(pathlib.Path(sys.argv[1]).glob("*.json")):
    data = json.loads(report.read_text())
    counts = dict.fromkeys(order, 0)
    blocked = 0
    for result in data.get("Results", []):
        for v in result.get("Vulnerabilities") or []:
            counts[v.get("Severity", "UNKNOWN")] = counts.get(v.get("Severity", "UNKNOWN"), 0) + 1
            # D7: solo bloquea lo grave que ya tiene arreglo; lo que nadie puede corregir todavía no detiene la CI
            if v.get("Severity") in ("CRITICAL", "HIGH") and v.get("FixedVersion"):
                blocked += 1
                blocking.append((data["ArtifactName"], v["Severity"], v["VulnerabilityID"], v["PkgName"],
                                 v["InstalledVersion"], v["FixedVersion"]))
    print(f"| `{data['ArtifactName']}` | " + " | ".join(str(counts[s]) for s in order) + f" | {blocked} |")

if blocking:
    print("\n**Bloquean** (CRITICAL o HIGH con arreglo): subir la base o la dependencia en platform-bom (CONTRIBUTING.md)\n")
    for image, severity, cve, package, installed, fixed in sorted(set(blocking)):
        print(f"- `{image}` {severity} {cve}: {package} {installed} → {fixed}")
    sys.exit(1)
print("\nNinguna CRITICAL ni HIGH con arreglo.")
EOF
