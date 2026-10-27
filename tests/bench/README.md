# Medición de accounts: Spring JVM, Quarkus JVM y Quarkus nativo (BANK-110)

`accounts_bench.py` mide las tres versiones del mismo accounts con las mismas reglas. Solo usa la biblioteca estándar de Python y la CLI de Docker.

```bash
# Con el entorno local en bootRun (infra/start-local.sh) y las tres imágenes construidas:
infra/build-images.sh accounts-service accounts-service-quarkus
docker build -f accounts-service-quarkus/Dockerfile.native -t bank-accounts-service-quarkus-native:local .
docker stop bank-fraud-service   # si corre: que no compita por la CPU durante la carga
python3 -I tests/bench/accounts_bench.py --runs 5 --warmup 30 --seconds 30 --threads 8 --out .local/bench.json
```

## Método
- **Mismas condiciones:**
    - cada corrida levanta la versión en un contenedor nuevo, con `--memory 768m --cpus 2`;
    - misma red, misma base, mismo Kafka y mismo core emulado del compose;
    - el config-server y customers son los de bootRun, por el host;
    - sin Eureka, así el gateway no le manda tráfico.
- **Arranque:** de `docker start` a la readiness en 200, preguntando cada 20 ms.
- **Primera petición:** `GET /accounts/me` apenas está listo. En frío pasa por gRPC a customers y por la base.
- **Memoria:** la del contenedor (cgroup, `docker stats`), en reposo a los 20 s y el pico durante la carga.
- **Carga:**
    - 8 hilos transfieren 0.01 USD en anillo entre 8 cuentas de banco de pruebas de Ana (`.local/bench-accounts.json`, se abren la primera vez), para no concentrar los bloqueos en una sola fila;
    - 30 s de calentamiento que **no se miden**, porque el JIT de las JVM compila en esos segundos, y después 30 s medidos: p50/p95 de `POST /transfers` (solo las 201) y transferencias/s;
    - además, una corrida sostenida de 3 minutos por versión.
- **Después de cada carga:**
    - la suma de todos los saldos no cambió;
    - no quedó ninguna `PENDING`;
    - cada transferencia completada llegó a `bank.transfers.v1` **exactamente una vez** (event-id contados en Kafka entre los offsets de antes y de después).
- **Imagen:** `docker save | gzip -1`, lo que se subiría a un registro.

Resultados del 2026-10-26 (MacBook, Docker Desktop con 10 CPU y 7.7 GB): `results-2026-10-26.json` (5 corridas) y `results-2026-10-26-sostenido.json` (3 minutos). La tabla y la conclusión están en el documento de arquitectura ("Quarkus vs Spring con números").

**Límites:**
- Es una laptop, no un clúster: la dispersión de las transferencias/s entre corridas es grande (por ejemplo, 173 a 301 en Spring). Por eso se informa la mediana con el mínimo y el máximo.
- El cuello de botella común es la base (bloqueo de la fila de la cuenta, `SELECT … FOR UPDATE`) y el core emulado, no el framework.
- El binario nativo usa la biblioteca de clases del **JDK 25** (Mandrel 25, la que pide Quarkus 3.40); las dos versiones JVM corren en el **JDK 21**.
