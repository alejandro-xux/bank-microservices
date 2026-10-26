"""Deja el emulador de Cosmos como está el Cosmos real (BANK-96, spike BANK-91 C7): la base `bank`, el contenedor
`customers` (partition key y unique key /documentKey; la app no lo crea: autoCreateContainer=false) y los clientes de
prueba de customers.json, con sus ids fijos. Idempotente: si ya existen, los deja igual (upsert de los documentos).

Uso: python3 -I infra/cosmos-emulator/init.py [endpoint]      (por defecto https://localhost:8081)
Solo biblioteca estándar: la API REST de Cosmos firmada con la clave. La clave por defecto es la del emulador,
pública y fija (la documenta Microsoft): no es un secreto y no sirve contra un Cosmos real.
"""
import base64
import datetime
import hashlib
import hmac
import json
import pathlib
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

EMULATOR_KEY = "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU9DaOtpvXTGaw2DStuL8HEBjrUXXMTwrS8iwPYr+lbwz5Y5Q=="
ENDPOINT = (sys.argv[1] if len(sys.argv) > 1 else "https://localhost:8081").rstrip("/")
DATABASE = "bank"
CONTAINER = "customers"
# El emulador usa un certificado propio: solo para él se acepta sin verificar
INSECURE = ssl._create_unverified_context()


def request(verb, resource_type, resource_link, path, body=None, extra_headers=None):
    date = datetime.datetime.now(datetime.timezone.utc).strftime("%a, %d %b %Y %H:%M:%S GMT")
    payload = f"{verb.lower()}\n{resource_type}\n{resource_link}\n{date.lower()}\n\n"
    signature = base64.b64encode(
        hmac.new(base64.b64decode(EMULATOR_KEY), payload.encode(), hashlib.sha256).digest()).decode()
    headers = {
        "Authorization": urllib.parse.quote(f"type=master&ver=1.0&sig={signature}"),
        "x-ms-date": date,
        "x-ms-version": "2018-12-31",
        "Content-Type": "application/json",
        **(extra_headers or {}),
    }
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"{ENDPOINT}/{path}", data=data, headers=headers, method=verb)
    try:
        with urllib.request.urlopen(req, context=INSECURE, timeout=30) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


def wait_for_emulator():
    for _ in range(60):
        try:
            if request("GET", "dbs", "", "dbs") == 200:
                return
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            pass
        time.sleep(2)
    sys.exit(f"✗ el emulador de Cosmos no respondió en {ENDPOINT}")


def main():
    wait_for_emulator()
    # 201 = creado, 409 = ya existía: los dos sirven
    status = request("POST", "dbs", "", "dbs", {"id": DATABASE})
    if status not in (201, 409):
        sys.exit(f"✗ no se pudo crear la base {DATABASE}: HTTP {status}")
    status = request("POST", "colls", f"dbs/{DATABASE}", f"dbs/{DATABASE}/colls", {
        "id": CONTAINER,
        "partitionKey": {"paths": ["/documentKey"], "kind": "Hash"},
        "uniqueKeyPolicy": {"uniqueKeys": [{"paths": ["/documentKey"]}]},
    })
    if status not in (201, 409):
        sys.exit(f"✗ no se pudo crear el contenedor {CONTAINER}: HTTP {status}")
    customers = json.loads((pathlib.Path(__file__).parent / "customers.json").read_text(encoding="utf-8"))
    for customer in customers:
        status = request("POST", "docs", f"dbs/{DATABASE}/colls/{CONTAINER}", f"dbs/{DATABASE}/colls/{CONTAINER}/docs",
                         customer, {"x-ms-documentdb-partitionkey": json.dumps([customer["documentKey"]]),
                                    "x-ms-documentdb-is-upsert": "True"})
        if status not in (200, 201):
            sys.exit(f"✗ no se pudo guardar el cliente {customer['id']}: HTTP {status}")
    print(f"• emulador de Cosmos listo: {DATABASE}/{CONTAINER} con {len(customers)} clientes de prueba")


if __name__ == "__main__":
    main()
