# perf

Medición de la comunicación accounts → customers (BANK-63). Necesita el entorno local arriba (`infra/start-local.sh`).

```bash
cd tests/perf
./gradlew run                                          # 1000 llamadas por caso, 100 de calentamiento
./gradlew run --args="--iterations 2000 --warmup 200"
```

Mide, en un solo hilo y con conexiones reutilizadas:

1. **La llamada interna sola**: REST `GET /customers/{id}` frente a gRPC `GetCustomer`, con el mismo cache de Redis detrás. Informa la latencia p50/p95 y los bytes del cuerpo de la respuesta (sin headers). Además muestra cuánto ocuparían en JSON los mismos dos campos del mensaje gRPC, para comparar solo la codificación.
2. **El camino completo de Ana**: `GET /accounts/me` (accounts → customers por gRPC, con el cache del vínculo).

No pasa por el gateway: su rate limit (10 por segundo) cortaría la medición.

**Límites**: todo corre en local, en una sola máquina, sin TLS y con un solo cliente. Cosmos está en Azure (Brazil South), así que un miss de cache incluye el viaje de red. Son números para **comparar** caminos entre sí, no para estimar la capacidad en producción.
