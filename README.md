# Bank Microservices

Sistema bancario de microservicios para practicar Spring Boot, Spring Cloud,
programación reactiva, Kafka, Azure y Quarkus.

## Componentes
| Servicio | Stack |
|---|---|
| customers-service | Spring WebFlux + Cosmos DB + Redis (cache) |
| accounts-service | Spring MVC + JPA + PostgreSQL |
| notifications-service | Spring Kafka + Resilience4j |

## Entorno local
Redis corre en Docker en el puerto **6380** (el 6379 puede estar ocupado por otros proyectos).

```bash
docker compose -f infra/docker-compose.yml up -d   # levanta Redis
docker compose -f infra/docker-compose.yml ps      # debe decir "healthy"
docker compose -f infra/docker-compose.yml down    # lo apaga
```

customers-service necesita las variables `COSMOS_ENDPOINT` y `COSMOS_KEY`; `REDIS_HOST` y `REDIS_PORT` son opcionales (por defecto `localhost:6380`).

```bash
cd customers-service
./gradlew bootRun
```

## Gestión
- Jira: proyecto `BANK`
- Ramas: `feature/BANK-<n>-<descripcion>`
