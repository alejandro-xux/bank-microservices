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

### customers-service
Necesita las variables `COSMOS_ENDPOINT` y `COSMOS_KEY`. Son opcionales `REDIS_HOST` y `REDIS_PORT` (por defecto `localhost:6380`) y `AUTH_ISSUER_URI` y `AUTH_AUDIENCE` (por defecto el Keycloak local: `http://localhost:8180/realms/bank` y `customers-service`; en Azure apuntan a Microsoft Entra).

```bash
cd customers-service
./gradlew bootRun
```

La API exige un token (`Authorization: Bearer …`); sin él, o con uno inválido, vencido o de otra audiencia, responde 401. Solo `/actuator/health/**` y la documentación (`/v3/api-docs`, Swagger UI) son públicos. Con el `TOKEN` obtenido arriba:

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/customers/c15a442d-5146-471e-8804-746f4b42253b
```

Los permisos salen del claim `roles` del token; si el rol no alcanza, la respuesta es 403:

| Endpoint | `ADMIN` | `CUSTOMER` |
|---|---|---|
| `POST /customers`, `GET /customers?dni=`, `GET /customers/search` | ✅ | 403 |
| `GET` y `PATCH /customers/{id}` | ✅ cualquiera (404 si no existe) | solo el suyo (`identityId` = su `oid`) |
| `GET /customers/me` (mis datos, sin conocer el id) | 403 (no es cliente) | ✅ el vinculado a su `oid`; sin vínculo → 403 |

Un `CUSTOMER` que pide otro cliente o un id que no existe recibe el mismo 403, para no revelar qué clientes existen.

Los 401 y 403 responden en formato ProblemDetail (`application/problem+json`), igual que el resto de errores; el 401 mantiene el header estándar `WWW-Authenticate`.

**Probar desde Swagger** (http://localhost:8081/swagger-ui.html): botón **Authorize** → bloque *authorization_code with PKCE* (cliente `bank-web`, sin secreto) → **Authorize** → iniciar sesión en Keycloak como `ana` o `asesor`. Desde ahí, *Try it out* envía el token en cada llamada. En Azure se usa el mismo botón con Entra, definiendo `AUTH_ISSUER_URI` y `SWAGGER_CLIENT_ID`.

### Pruebas
```bash
cd customers-service
./gradlew test
```
No necesitan credenciales de Azure (Cosmos está simulado), pero sí Docker encendido: Testcontainers levanta Redis para las pruebas de cache y un Keycloak con `infra/keycloak/bank-realm.json` para probar la seguridad con tokens reales (firma, `iss`, `aud`, roles y `oid`).

## Gestión
- Jira: proyecto `BANK`
- Ramas: `feature/BANK-<n>-<descripcion>`
