#!/usr/bin/env bash
# Antes de abrir o actualizar un PR (BANK-106): corre en local lo que la CI va a revisar de un módulo, para que la CI
# confirme y no descubra (retro del Sprint 10). Termina con error si algo no pasaría:
#   1. las pruebas del módulo (el resumen sale de los XML de JUnit, no de la salida de Gradle);
#   2. la cobertura de las líneas cambiadas respecto de main, con la fórmula y el umbral del Quality Gate de Sonar
#      (líneas + condiciones, 80 %; con menos de 20 líneas nuevas Sonar no evalúa la cobertura y aquí tampoco);
#   3. si el módulo tiene imagen: su construcción y revisión (infra/build-images.sh) y Trivy (infra/scan-images.sh).
# Uso: infra/pre-push.sh accounts-service-quarkus [--skip-image]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE=${1:?"uso: infra/pre-push.sh <módulo> [--skip-image]"}
SKIP_IMAGE=${2:-}
THRESHOLD=80
MIN_NEW_LINES=20
[ -d "$ROOT/$MODULE" ] || { echo "✗ no existe el módulo $MODULE" >&2; exit 1; }

echo "▶ Pruebas de $MODULE"
(cd "$ROOT/$MODULE" && ./gradlew test --no-daemon -q > /dev/null 2>&1) || true
python3 -I - "$ROOT/$MODULE/build/test-results/test" <<'EOF'
import pathlib, re, sys
total = failed = 0
for report in pathlib.Path(sys.argv[1]).glob("*.xml"):
    head = re.search(r'<testsuite[^>]*tests="(\d+)"[^>]*failures="(\d+)"[^>]*errors="(\d+)"', report.read_text())
    total += int(head[1]); failed += int(head[2]) + int(head[3])
    if int(head[2]) + int(head[3]):
        print(f"  ✗ {report.stem.removeprefix('TEST-')}")
if total == 0:
    sys.exit("✗ no hay resultados de pruebas: el build falló antes de correrlas")
print(f"  {total} pruebas, {failed} con fallos")
sys.exit(1 if failed else 0)
EOF

echo "▶ Cobertura de las líneas cambiadas respecto de main"
# El XML que lee Sonar: el de gradle/quality.gradle en los módulos Spring, el de quarkus-jacoco en los de Quarkus
XML=$(ls "$ROOT/$MODULE"/build/reports/jacoco/test/jacocoTestReport.xml "$ROOT/$MODULE"/build/reports/jacoco/test/jacoco.xml 2>/dev/null | head -1 || true)
[ -n "$XML" ] || { echo "✗ no hay reporte de JaCoCo en $MODULE/build/reports/jacoco/test" >&2; exit 1; }
git -C "$ROOT" fetch -q origin main 2>/dev/null || true
BASE=$(git -C "$ROOT" merge-base origin/main HEAD)
# El diff incluye lo que todavía no está en un commit: se revisa lo que se va a subir. Va a un archivo porque la
# entrada estándar de Python es el propio script
DIFF=$(mktemp)
trap 'rm -f "$DIFF"' EXIT
git -C "$ROOT" diff -U0 "$BASE" -- "$MODULE/src/main/java" > "$DIFF"
# Los archivos nuevos que todavía no están en git no salen en el diff: cuentan enteros como líneas nuevas
git -C "$ROOT" ls-files --others --exclude-standard -- "$MODULE/src/main/java" | while read -r new_file; do
  printf '+++ b/%s\n@@ -0,0 +1,%s @@\n' "$new_file" "$(wc -l < "$ROOT/$new_file" | tr -d ' ')" >> "$DIFF"
done
python3 -I - "$XML" "$DIFF" "$THRESHOLD" "$MIN_NEW_LINES" <<'EOF'
import re, sys, xml.etree.ElementTree as ET
xml_path, diff_path, threshold, min_lines = sys.argv[1], sys.argv[2], float(sys.argv[3]), int(sys.argv[4])

# Líneas agregadas o cambiadas por archivo, a partir del diff unificado
changed, current = {}, None
for line in open(diff_path, encoding="utf-8"):
    if line.startswith("+++ "):
        path = line[6:].strip() if line.startswith("+++ b/") else None
        current = path if path and not path.endswith("Application.java") else None  # sonar.coverage.exclusions
        continue
    hunk = re.match(r"@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", line)
    if hunk and current:
        start, count = int(hunk[1]), int(hunk[2] or 1)
        changed.setdefault(current, set()).update(range(start, start + count))

# Lo que JaCoCo sabe de cada línea: mi/ci = instrucciones sin cubrir/cubiertas, mb/cb = condiciones
report = {}
for package in ET.parse(xml_path).getroot().iter("package"):
    for source in package.findall("sourcefile"):
        lines = {int(l.get("nr")): l for l in source.findall("line")}
        report[f"{package.get('name')}/{source.get('name')}"] = lines

lines_total = lines_covered = cond_total = cond_covered = 0
uncovered = []
for path, numbers in sorted(changed.items()):
    key = path.split("src/main/java/", 1)[-1]
    lines = report.get(key, {})
    for nr in sorted(numbers):
        line = lines.get(nr)
        if line is None:
            continue  # no ejecutable (comentario, firma, import)
        hit = int(line.get("ci")) > 0
        lines_total += 1
        lines_covered += hit
        branches = int(line.get("mb")) + int(line.get("cb"))
        cond_total += branches
        cond_covered += int(line.get("cb"))
        if not hit or int(line.get("mb")):
            uncovered.append(f"{key}:{nr}" + ("" if not hit else f" ({line.get('mb')} condiciones sin cubrir)"))

if lines_total == 0:
    print("  no hay líneas ejecutables nuevas")
    sys.exit(0)
# La fórmula de Sonar: (líneas cubiertas + condiciones cubiertas) / (líneas + condiciones)
coverage = 100 * (lines_covered + cond_covered) / (lines_total + cond_total)
print(f"  {coverage:.1f} % en {lines_total} líneas nuevas ({cond_covered}/{cond_total} condiciones)")
for item in uncovered[:20]:
    print(f"    sin cubrir: {item}")
if lines_total < min_lines:
    print(f"  menos de {min_lines} líneas nuevas: Sonar no evalúa la cobertura todavía")
    sys.exit(0)
if coverage < threshold:
    sys.exit(f"✗ {coverage:.1f} % < {threshold:.0f} %: el Quality Gate de Sonar lo frenaría")
EOF

if [ -f "$ROOT/$MODULE/Dockerfile" ] && [ "$SKIP_IMAGE" != "--skip-image" ]; then
  echo "▶ Imagen y Trivy"
  "$ROOT/infra/build-images.sh" "$MODULE"
  "$ROOT/infra/scan-images.sh" "bank-$MODULE:local"
fi
echo "✅ $MODULE listo para el PR"
