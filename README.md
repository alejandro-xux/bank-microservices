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
Redis y Keycloak corren en Docker: Redis en el puerto **6380** (el 6379 puede estar ocupado por otros proyectos) y Keycloak en el **8180**.

```bash
docker compose -f infra/docker-compose.yml up -d   # levanta Redis y Keycloak
docker compose -f infra/docker-compose.yml ps      # ambos deben decir "healthy" (Keycloak tarda ~30 s)
docker compose -f infra/docker-compose.yml down    # los apaga
```

### Keycloak (identidad, solo local)
En Azure la identidad la da Microsoft Entra; Keycloak lo reemplaza en local y emite tokens con la misma forma (claims `roles`, `oid` y `aud`).

- Consola: http://localhost:8180/admin (usuario `admin` / `admin`), realm `bank`.
- El realm se importa al arrancar desde `infra/keycloak/bank-realm.json`. Los cambios hechos en la consola se pierden al recrear el contenedor; lo que deba quedar se edita en el JSON y se aplica con `docker compose -f infra/docker-compose.yml up -d --force-recreate keycloak`.

Usuarios de prueba (contraseñas solo locales, no son secretos):

| Usuario | Contraseña | Rol | `oid` |
|---|---|---|---|
| `ana` | `ana-local` | `CUSTOMER` | `4f8c2a1e-6b3d-4c9a-9e7f-2d5b8a1c3e01` |
| `asesor` | `asesor-local` | `ADMIN` | `7b2e9d4c-1a5f-4e8b-b3c6-9f0a2d4e6b02` |

Obtener un token (cliente `bank-cli`, password grant solo en local) y ver sus claims:

```bash
TOKEN=$(curl -s -X POST http://localhost:8180/realms/bank/protocol/openid-connect/token \
  -d grant_type=password -d client_id=bank-cli -d username=ana -d password=ana-local \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

echo "$TOKEN" | cut -d. -f2 | python3 -c "import sys,json,base64; p=sys.stdin.read().strip(); print(json.dumps(json.loads(base64.urlsafe_b64decode(p+'='*(-len(p)%4))), indent=2))"
```

El token dura 5 minutos. El vínculo cuenta ↔ cliente es el campo `identityId` del documento del cliente en Cosmos, que debe ser igual al `oid` del usuario. En local se agrega a mano en Azure Portal → Cosmos DB → Data Explorer → `bank` / `customers`: abrir el documento de Ana (DNI 12345678), añadir `"identityId": "4f8c2a1e-6b3d-4c9a-9e7f-2d5b8a1c3e01"` y guardar.

customers-service necesita las variables `COSMOS_ENDPOINT` y `COSMOS_KEY`; `REDIS_HOST` y `REDIS_PORT` son opcionales (por defecto `localhost:6380`).

```bash
cd customers-service
./gradlew bootRun
```

## Gestión
- Jira: proyecto `BANK`
- Ramas: `feature/BANK-<n>-<descripcion>`
