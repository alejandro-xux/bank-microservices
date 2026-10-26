"""Carga de transferencias por el gateway para ensayar el canary de accounts (BANK-104).

Manda transferencias chicas en los dos sentidos entre las dos cuentas en dólares de Ana del seed local, con su token.
No toca las cuentas en soles: la E2E de Karate (tests/e2e) mide exactamente los saldos de esas, y puede correr al mismo
tiempo. Los montos nunca terminan en .13, .98 o .99 (las reglas del core emulado), así que toda transferencia debería
quedar COMPLETED. La segunda cuenta en dólares empieza casi vacía: antes del ensayo, pasarle 100.00 desde la primera.
Escribe una línea JSON por petición (momento, sentido, código HTTP, X-Served-By, duración) para contar el reparto
por fase y los errores que vio el cliente.

Uso (modo contenedores, desde la raíz del repo):
    python3 -I tests/canary/carga.py --segundos 300 --por-segundo 4 --salida /tmp/carga.jsonl
    python3 -I tests/canary/carga.py --resumen /tmp/carga.jsonl --fases fases.txt
fases.txt: una línea por fase, "<epoch> <nombre>", en el momento en que empieza (la escribe quien mueve los pesos).
"""
import argparse
import json
import random
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

KEYCLOAK = "http://localhost:8180/realms/bank/protocol/openid-connect/token"
# Las dos cuentas en dólares de Ana en el entorno local (infra/seed-local.sh y la apertura de BANK-55)
USD_1 = ("dfc7fbe1-d001-4c05-8150-cbe218f7a685", "001-0000000002-4")
USD_2 = ("38bcc244-a879-49dc-8663-da93832f2fa4", "001-0000000004-0")
AMOUNTS = ["0.01", "0.02", "0.05", "0.10"]


def token(username, password):
    form = urllib.parse.urlencode({"grant_type": "password", "client_id": "bank-cli",
                                   "username": username, "password": password}).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=form), timeout=10) as response:
        return json.load(response)["access_token"]


class Tokens:
    """El token de Ana dura 5 minutos: se pide de nuevo cada 3."""

    def __init__(self):
        self._lock = threading.Lock()
        self._at = 0.0
        self._token = None

    def get(self):
        with self._lock:
            if time.time() - self._at > 180:
                self._token = token("ana", "ana-local")
                self._at = time.time()
            return self._token


def transfer(gateway, tokens, n):
    forward = n % 2 == 0
    origin, destination = (USD_1, USD_2) if forward else (USD_2, USD_1)
    body = json.dumps({"fromAccountId": origin[0], "toAccountNumber": destination[1],
                       "amount": random.choice(AMOUNTS)}).encode()
    request = urllib.request.Request(gateway + "/api/transfers", data=body, method="POST", headers={
        "Authorization": "Bearer " + tokens.get(), "Content-Type": "application/json",
        "Idempotency-Key": str(uuid.uuid4())})
    start = time.time()
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            status, served_by = response.status, response.headers.get("X-Served-By")
    except urllib.error.HTTPError as error:
        status, served_by = error.code, error.headers.get("X-Served-By")
    except OSError as error:
        status, served_by = 0, type(error).__name__
    return {"t": round(start, 3), "sentido": "1>2" if forward else "2>1", "codigo": status,
            "servedBy": served_by, "ms": round((time.time() - start) * 1000)}


def run(args):
    tokens = Tokens()
    lines = []
    lock = threading.Lock()
    end = time.time() + args.segundos
    with ThreadPoolExecutor(max_workers=16) as pool, open(args.salida, "w", encoding="utf-8") as out:
        def done(future):
            with lock:
                out.write(json.dumps(future.result()) + "\n")
                out.flush()
                lines.append(future.result()["codigo"])
        n = 0
        while time.time() < end:
            pool.submit(transfer, args.gateway, tokens, n).add_done_callback(done)
            n += 1
            time.sleep(1 / args.por_segundo)
    codes = Counter(lines)
    print(f"{len(lines)} transferencias: " + ", ".join(f"{code}: {count}" for code, count in sorted(codes.items())))


def summary(args):
    records = [json.loads(line) for line in open(args.resumen, encoding="utf-8")]
    phases = [line.split(maxsplit=1) for line in open(args.fases, encoding="utf-8") if line.strip()] if args.fases else []
    phases = [(float(t), name.strip()) for t, name in phases] or [(0.0, "todo")]
    print("| Fase | Transferencias | Spring | Quarkus | % Quarkus | Errores (código) | p95 ms |\n|---|---|---|---|---|---|---|")
    errors_total = 0
    for i, (start, name) in enumerate(phases):
        stop = phases[i + 1][0] if i + 1 < len(phases) else float("inf")
        rows = [r for r in records if start <= r["t"] < stop]
        if not rows:
            continue
        served = Counter(r["servedBy"] for r in rows if r["codigo"] in (201, 202))
        errors = Counter(r["codigo"] for r in rows if r["codigo"] not in (201, 202))
        errors_total += sum(errors.values())
        spring, quarkus = served.get("accounts-service", 0), served.get("accounts-service-quarkus", 0)
        p95 = sorted(r["ms"] for r in rows)[int(len(rows) * 0.95) - 1] if len(rows) >= 20 else max(r["ms"] for r in rows)
        share = f"{100 * quarkus / (spring + quarkus):.1f} %" if spring + quarkus else "-"
        print(f"| {name} | {len(rows)} | {spring} | {quarkus} | {share} | "
              f"{', '.join(f'{c}: {n}' for c, n in sorted(errors.items())) or 'ninguno'} | {p95} |")
    sys.exit(1 if errors_total else 0)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--gateway", default="http://localhost:8080")
    parser.add_argument("--segundos", type=int, default=300)
    parser.add_argument("--por-segundo", type=float, default=4)
    parser.add_argument("--salida", default="carga.jsonl")
    parser.add_argument("--resumen")
    parser.add_argument("--fases")
    parsed = parser.parse_args()
    summary(parsed) if parsed.resumen else run(parsed)
