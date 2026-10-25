"""Resumen de las pruebas de un módulo para el PR (BANK-83), leído de los XML de JUnit.

Regla de CONTRIBUTING: el resultado se lee en los XML (cuántas pruebas, cuáles fallaron y con qué mensaje), no en
"BUILD SUCCESSFUL". Uso: python3 test-summary.py <módulo>  (desde la raíz del repo; escribe Markdown a stdout)
"""
import glob
import sys
import xml.etree.ElementTree as ET

module = sys.argv[1]
reports = glob.glob(f"{module}/build/test-results/test/*.xml")

tests = failed = skipped = 0
seconds = 0.0
failures = []
for path in reports:
    suite = ET.parse(path).getroot()
    tests += int(suite.get("tests"))
    skipped += int(suite.get("skipped"))
    seconds += float(suite.get("time"))
    for case in suite.iter("testcase"):
        problem = case.find("failure")
        if problem is None:
            problem = case.find("error")
        if problem is not None:
            failed += 1
            message = (problem.get("message") or "").strip().splitlines()
            failures.append((case.get("classname").split(".")[-1], case.get("name"), message[0][:200] if message else ""))

icon = "❌" if failed or not reports else "✅"
print(f"### {icon} {module}")
if not reports:
    # Sin XML: no compiló o no llegó a correr ninguna prueba. No es un "verde"
    print("No hay resultados de pruebas (¿falló la compilación?). Ver el log del paso Pruebas.")
else:
    print(f"{tests} pruebas · {failed} fallos · {skipped} omitidas · {seconds:.0f} s de pruebas")
    for clazz, name, message in failures:
        print(f"- **{clazz}.{name}**: `{message}`")
