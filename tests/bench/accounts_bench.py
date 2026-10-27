"""Mide las tres versiones de accounts con las mismas reglas (BANK-110): Spring JVM, Quarkus JVM y Quarkus nativo.

Cada corrida levanta la versión en un contenedor NUEVO, con el mismo mem_limit, las mismas CPU y las mismas variables,
contra la infraestructura del compose y los servicios de bootRun (config-server y customers por el host). Sin Eureka:
el gateway no le manda tráfico. Mide:
  - arranque: de `docker start` a la readiness en 200
  - primera petición: GET /accounts/me apenas está listo (gRPC a customers + base, en frío)
  - memoria del contenedor (cgroup) en reposo y el pico durante la carga
  - carga: primero W segundos de calentamiento que no se miden (el JIT de las JVM compila en esos segundos; medir
    desde el primero compara una JVM a medio compilar), después N hilos durante S segundos, cada uno transfiere 0.01 USD en anillo entre cuentas de banco de pruebas de
    Ana (así los bloqueos no se concentran en una fila): p50/p95 de POST /transfers y transferencias/s
  - después de la carga: la suma de los saldos no cambió, no quedó ninguna PENDING, y cada transferencia completada
    llegó a Kafka exactamente una vez (por event-id)
Uso (desde la raíz, con el entorno local en bootRun y Docker):
  python3 -I tests/bench/accounts_bench.py --runs 5 --warmup 30 --seconds 30 --threads 8 --out .local/bench.json
Solo la biblioteca estándar de Python y la CLI de Docker. Los usuarios son los de prueba del realm local.
"""
import argparse
import json
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

NETWORK = "bank-microservices_default"
HOST_PORT = 18080
MEM_LIMIT = "768m"
CPUS = "2"
KEYCLOAK = "http://localhost:8180/realms/bank/protocol/openid-connect/token"
BENCH_ACCOUNTS_FILE = Path(".local/bench-accounts.json")
ANA_CUSTOMER = "c15a442d-5146-471e-8804-746f4b42253b"

COMMON_ENV = {
    "CONFIG_SERVER_URL": "http://host.docker.internal:8888",
    "DB_HOST": "postgres",
    "DB_PORT": "5432",
    "KAFKA_BOOTSTRAP_SERVERS": "kafka:9092",
    "CORE_BANKING_URL": "http://core-banking-mock:8080",
    "AUTH_ISSUER_URI": "http://localhost:8180/realms/bank",
}
VARIANTS = {
    "spring-jvm": {
        "image": "bank-accounts-service:local", "port": 8082,
        "env": {**COMMON_ENV, "EUREKA_CLIENT_ENABLED": "false",
                "CUSTOMERS_GRPC_ADDRESS": "static://host.docker.internal:9091",
                "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI":
                    "http://keycloak:8080/realms/bank/protocol/openid-connect/certs"},
    },
    "quarkus-jvm": {
        "image": "bank-accounts-service-quarkus:local", "port": 8084,
        "env": {**COMMON_ENV, "CUSTOMERS_GRPC_HOST": "host.docker.internal",
                "AUTH_JWKS_URI": "http://keycloak:8080/realms/bank/protocol/openid-connect/certs"},
    },
    "quarkus-native": {
        "image": "bank-accounts-service-quarkus-native:local", "port": 8084,
        "env": {**COMMON_ENV, "CUSTOMERS_GRPC_HOST": "host.docker.internal",
                "AUTH_JWKS_URI": "http://keycloak:8080/realms/bank/protocol/openid-connect/certs"},
    },
}


def sh(*args, check=True):
    return subprocess.run(args, check=check, capture_output=True, text=True).stdout.strip()


def psql(sql, db="accounts"):
    return sh("docker", "exec", "bank-postgres", "psql", "-U", "postgres", "-d", db, "-tAc", sql)


def token(user, password):
    data = urllib.parse.urlencode({"grant_type": "password", "client_id": "bank-cli",
                                   "username": user, "password": password}).encode()
    with urllib.request.urlopen(KEYCLOAK, data, timeout=10) as r:
        return json.load(r)["access_token"]


def call(method, url, tok, body=None, timeout=30):
    req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Authorization", "Bearer " + tok)
    if body is not None:
        req.add_header("Content-Type", "application/json")
        req.add_header("Idempotency-Key", str(uuid.uuid4()))
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def bench_accounts(threads):
    """Las cuentas de banco de pruebas de Ana (USD, 500.00 cada una), abiertas una sola vez por el asesor."""
    known = json.loads(BENCH_ACCOUNTS_FILE.read_text()) if BENCH_ACCOUNTS_FILE.exists() else []
    if len(known) >= threads:
        return known[:threads]
    admin = token("asesor", "asesor-local")
    gateway = "http://localhost:8080/api"
    while len(known) < threads:
        status, body = call("POST", gateway + "/accounts", admin,
                            {"customerId": ANA_CUSTOMER, "type": "AHORROS", "currency": "USD"})
        assert status == 201, (status, body)
        account = json.loads(body)
        status, body = call("POST", f"{gateway}/accounts/{account['id']}/deposits", admin, {"amount": "500.00"})
        assert status == 201, (status, body)
        known.append({"id": account["id"], "number": account["number"]})
    BENCH_ACCOUNTS_FILE.parent.mkdir(exist_ok=True)
    BENCH_ACCOUNTS_FILE.write_text(json.dumps(known, indent=2))
    return known


def mem_mib(name):
    usage = sh("docker", "stats", "--no-stream", "--format", "{{.MemUsage}}", name).split("/")[0].strip()
    value = float("".join(c for c in usage if c.isdigit() or c == "."))
    return value * (1024 if usage.endswith("GiB") else 1)


def kafka_end_offsets():
    out = sh("docker", "exec", "bank-kafka", "/opt/kafka/bin/kafka-get-offsets.sh", "--bootstrap-server",
             "localhost:9092", "--topic", "bank.transfers.v1")
    return {int(p): int(o) for _, p, o in (line.split(":") for line in out.splitlines())}


def kafka_event_ids(start, end):
    ids = []
    for partition, first in start.items():
        count = end[partition] - first
        if count <= 0:
            continue
        out = sh("docker", "exec", "bank-kafka", "/opt/kafka/bin/kafka-console-consumer.sh", "--bootstrap-server",
                 "localhost:9092", "--topic", "bank.transfers.v1", "--partition", str(partition), "--offset",
                 str(first), "--max-messages", str(count), "--timeout-ms", "20000",
                 "--formatter-property", "print.headers=true", "--formatter-property", "print.value=false",
                 check=False)
        for line in out.splitlines():
            for header in line.split("\t")[0].split(","):
                if header.startswith("event-id:"):
                    ids.append(header.split(":", 1)[1])
    return ids


def wait_ready(url, deadline):
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=1) as r:
                if r.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(0.02)
    return False


def one_run(variant, cfg, accounts, warmup, seconds, threads):
    name = f"bench-accounts-{variant}"
    sh("docker", "rm", "-f", name, check=False)
    env = [x for k, v in cfg["env"].items() for x in ("-e", f"{k}={v}")]
    sh("docker", "create", "--name", name, "--network", NETWORK, "--memory", MEM_LIMIT, "--cpus", CPUS,
       "--add-host", "host.docker.internal:host-gateway", "-p", f"{HOST_PORT}:{cfg['port']}", *env, cfg["image"])
    base = f"http://localhost:{HOST_PORT}"
    t0 = time.monotonic()
    sh("docker", "start", name)
    if not wait_ready(base + "/actuator/health/readiness", t0 + 180):
        logs = sh("docker", "logs", "--tail", "40", name, check=False)
        sh("docker", "rm", "-f", name, check=False)
        raise RuntimeError(f"{variant} no quedó listo:\n{logs}")
    startup = time.monotonic() - t0
    ana = token("ana", "ana-local")
    f0 = time.monotonic()
    status, _ = call("GET", base + "/accounts/me", ana)
    first = time.monotonic() - f0
    assert status == 200, status
    time.sleep(20)
    idle = mem_mib(name)

    money_before = psql("SELECT sum(balance) FROM account")
    offsets_before = kafka_end_offsets()
    started_at = psql("SELECT now()")
    latencies, codes, lock = [], {}, threading.Lock()
    measure_from = time.monotonic() + warmup
    stop_at = measure_from + seconds
    peak = [idle]

    def worker(i):
        src, dst = accounts[i], accounts[(i + 1) % len(accounts)]
        while time.monotonic() < stop_at:
            s = time.monotonic()
            code, _ = call("POST", base + "/transfers", ana,
                           {"fromAccountId": src["id"], "toAccountNumber": dst["number"], "amount": "0.01"})
            elapsed = time.monotonic() - s
            if s < measure_from:
                continue
            with lock:
                codes[code] = codes.get(code, 0) + 1
                if code == 201:
                    latencies.append(elapsed)

    def sampler():
        while time.monotonic() < stop_at:
            peak[0] = max(peak[0], mem_mib(name))
            time.sleep(1)

    pool = [threading.Thread(target=worker, args=(i,)) for i in range(threads)] + [threading.Thread(target=sampler)]
    [t.start() for t in pool]
    [t.join() for t in pool]

    # El relay publica cada 500 ms: se espera a que no quede nada sin publicar y a que no haya PENDING
    for _ in range(120):
        if psql("SELECT count(*) FROM outbox WHERE published_at IS NULL") == "0" and \
                psql("SELECT count(*) FROM transfer WHERE status = 'PENDING'") == "0":
            break
        time.sleep(0.5)
    money_after = psql("SELECT sum(balance) FROM account")
    pending = int(psql("SELECT count(*) FROM transfer WHERE status = 'PENDING'"))
    completed = int(psql(f"SELECT count(*) FROM transfer WHERE status = 'COMPLETED' AND created_at >= '{started_at}'"))
    ids = kafka_event_ids(offsets_before, kafka_end_offsets())
    sh("docker", "rm", "-f", name, check=False)

    latencies.sort()
    return {
        "startup_s": round(startup, 3), "first_request_ms": round(first * 1000, 1), "idle_mib": round(idle, 1),
        "load_peak_mib": round(peak[0], 1), "p50_ms": round(latencies[len(latencies) // 2] * 1000, 1),
        "p95_ms": round(latencies[int(len(latencies) * 0.95)] * 1000, 1),
        "transfers_per_s": round(len(latencies) / seconds, 1), "codes": codes,
        "money_unchanged": money_before == money_after, "pending_after": pending,
        "completed": completed, "events": len(ids), "distinct_events": len(set(ids)),
    }


def summary(runs, key):
    values = [r[key] for r in runs]
    return {"median": statistics.median(values), "min": min(values), "max": max(values)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--warmup", type=int, default=30)
    parser.add_argument("--seconds", type=int, default=30)
    parser.add_argument("--threads", type=int, default=8)
    parser.add_argument("--variants", default=",".join(VARIANTS))
    parser.add_argument("--out", default=".local/bench.json")
    args = parser.parse_args()
    accounts = bench_accounts(args.threads)
    result = {"config": {"runs": args.runs, "warmup": args.warmup, "seconds": args.seconds, "threads": args.threads,
                         "mem_limit": MEM_LIMIT, "cpus": CPUS}, "variants": {}}
    for variant in args.variants.split(","):
        cfg = VARIANTS[variant]
        size = int(sh("sh", "-c", f"docker save {cfg['image']} | gzip -1 | wc -c"))
        runs = []
        for n in range(args.runs):
            run = one_run(variant, cfg, accounts, args.warmup, args.seconds, args.threads)
            print(variant, n + 1, json.dumps(run), flush=True)
            runs.append(run)
        result["variants"][variant] = {
            "image_mb": round(size / 1e6, 1), "runs": runs,
            **{k: summary(runs, k) for k in ("startup_s", "first_request_ms", "idle_mib", "load_peak_mib",
                                              "p50_ms", "p95_ms", "transfers_per_s")},
            "all_money_unchanged": all(r["money_unchanged"] for r in runs),
            "all_exactly_once": all(r["events"] == r["distinct_events"] >= r["completed"] for r in runs),
            "pending_after_max": max(r["pending_after"] for r in runs),
        }
    Path(args.out).parent.mkdir(exist_ok=True)
    Path(args.out).write_text(json.dumps(result, indent=2))
    print(json.dumps({v: {k: d[k] for k in d if k != "runs"} for v, d in result["variants"].items()}, indent=2))


if __name__ == "__main__":
    main()
