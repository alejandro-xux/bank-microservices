# Bank Microservices

Sistema bancario de microservicios para practicar Spring Boot, Spring Cloud,
programación reactiva, Kafka, Azure y Quarkus.

## Componentes
| Servicio | Stack |
|---|---|
| config-server | Spring Cloud Config (backend native sobre `config-repo/`) |
| discovery-server | Spring Cloud Netflix Eureka (registro de servicios) |
| api-gateway | Spring Cloud Gateway Server WebFlux (punto de entrada único, valida el JWT) |
| customers-service | Spring WebFlux + Cosmos DB + Redis (cache) |
| accounts-service | Spring MVC + JPA + PostgreSQL |
| notifications-service | Spring Kafka + Resilience4j |

## Entorno local
**En un comando** (Docker encendido y las credenciales de Cosmos en `~/.bank-microservices.env`, o en la ruta que indique `BANK_ENV_FILE`):

```bash
infra/start-local.sh    # Redis + Keycloak → config-server → discovery-server → customers-service → api-gateway
infra/stop-local.sh     # apaga los servicios (con --all también Redis y Keycloak)
```

Espera a que cada pieza esté sana antes de seguir, salta las que ya están corriendo y termina cuando el gateway ya enruta a customers-service (~1 minuto desde cero). Los logs de cada servicio quedan en `.local/logs/`. Las secciones siguientes explican cada pieza por separado.

Redis y Keycloak corren en Docker: Redis en el puerto **6380** (el 6379 puede estar ocupado por otros proyectos) y Keycloak en el **8180**.

```bash
docker compose -f infra/docker-compose.yml up -d   # levanta Redis y Keycloak
docker compose -f infra/docker-compose.yml ps      # ambos deben decir "healthy" (Keycloak tarda ~30 s)
docker compose -f infra/docker-compose.yml down    # los apaga
```

### Keycloak (identidad, solo local)
En Azure la identidad la da Microsoft Entra; Keycloak lo reemplaza en local y emite tokens con la misma forma (claims `roles`, `oid` y `aud`). La audiencia es `bank-api`: un mismo token sirve para toda la API detrás del gateway.

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

El token dura 5 minutos. El vínculo cuenta ↔ cliente es el campo `identityId` del documento del cliente en Cosmos, que debe ser igual al `oid` del usuario. Lo crea el **asesor** desde la API (no se edita la base a mano):

```bash
ADM=$(curl -s -X POST http://localhost:8180/realms/bank/protocol/openid-connect/token \
  -d grant_type=password -d client_id=bank-cli -d username=asesor -d password=asesor-local \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# Vincular (200 con el nuevo ETag; admite If-Match)
curl -X PUT -H "Authorization: Bearer $ADM" -H "Content-Type: application/json" \
  -d '{"identityId":"4f8c2a1e-6b3d-4c9a-9e7f-2d5b8a1c3e01"}' \
  http://localhost:8080/api/customers/c15a442d-5146-471e-8804-746f4b42253b/identity

# Desvincular (204)
curl -X DELETE -H "Authorization: Bearer $ADM" \
  http://localhost:8080/api/customers/c15a442d-5146-471e-8804-746f4b42253b/identity
```

Solo `ADMIN` (un `CUSTOMER` recibe 403). Responde 409 si esa cuenta ya está en otro cliente o si el cliente ya tiene otra cuenta (hay que desvincular primero); repetir el mismo vínculo no cambia nada. El vínculo no aparece en las respuestas de la API.

### Versiones y configuración centralizadas
- **`platform-bom/`**: las versiones de todo el sistema (Spring Boot, Spring Cloud, Spring Cloud Azure, parches de Jackson, springdoc), como el BOM corporativo de un banco. Cada servicio es un proyecto Gradle independiente que lo importa con `includeBuild('../platform-bom')`; para cambiar una versión se edita solo ese archivo.
- **`config-repo/`**: la configuración que sirve el config-server. `application.yml` es la común a todos (emisor y audiencia del token, roles) y `<servicio>.yml` la de cada uno. El repositorio es público, así que **aquí nunca van secretos**: las credenciales siguen en variables de entorno.

### config-server
Se levanta **antes** que los servicios (puerto 8888):

```bash
cd config-server
./gradlew bootRun
curl http://localhost:8888/customers-service/default   # la configuración que recibirá customers-service
```

Lee `config-repo/` (otra carpeta con `CONFIG_REPO`). Un cambio en `config-repo/` se aplica reiniciando el servicio que lo usa.

### discovery-server (Eureka)
Registro de servicios en el puerto 8761: cada servicio se anota con su nombre (`spring.application.name`) y los demás lo buscan por ese nombre. Es independiente del config-server y conviene levantarlo antes que los servicios:

```bash
cd discovery-server
./gradlew bootRun
```

- Panel: http://localhost:8761 (ahí aparece `CUSTOMERS-SERVICE` en `UP`).
- API: `curl -H "Accept: application/json" http://localhost:8761/eureka/apps`. El registro tarda hasta 30 s en verse en la API (caché de Eureka).
- Si Eureka no está, los servicios arrancan igual y se registran solos cuando vuelve.
- La autopreservación está apagada solo en local, para que un servicio detenido desaparezca enseguida; en producción va encendida.
- En AKS (E12) Eureka se reemplaza por Services y DNS de Kubernetes.

### customers-service
Necesita el config-server arriba y las variables `COSMOS_ENDPOINT` y `COSMOS_KEY`. Son opcionales `CONFIG_SERVER_URL` (por defecto `http://localhost:8888`), `EUREKA_URL` (por defecto `http://localhost:8761/eureka`), `REDIS_HOST` y `REDIS_PORT` (por defecto `localhost:6380`) y `AUTH_ISSUER_URI` y `AUTH_AUDIENCE` (por defecto el Keycloak local: `http://localhost:8180/realms/bank` y `bank-api`; en Azure apuntan a Microsoft Entra).

```bash
cd customers-service
./gradlew bootRun
```

Si el config-server no responde, el servicio no arranca y lo indica en el log (`Could not locate PropertySource and the fail fast property is set`): es a propósito, para no arrancar sin configuración.

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

### api-gateway (punto de entrada)
La API se usa por el **puerto 8080** con el prefijo `/api`: `GET http://localhost:8080/api/customers/me` llega a customers-service como `GET /customers/me`. Necesita el config-server; encuentra a customers-service por su nombre en Eureka.

```bash
cd api-gateway
./gradlew bootRun
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/customers/me
```

- El gateway valida el token en el borde: sin token o con uno inválido responde 401 sin llamar al servicio.
- No revisa roles. Roles y dueño los decide customers-service, que además vuelve a validar el token (zero trust); sus 403 y 404 llegan tal cual.
- Las rutas están en `config-repo/api-gateway.yml`. `CUSTOMERS_SERVICE_URI` apunta la ruta a una URL fija en lugar de Eureka.
- Al arrancar, el gateway tarda hasta ~30 s en tener la lista de Eureka; mientras tanto la ruta puede responder 503.
- **Rate limiting por usuario** (claim `oid`): balde de fichas en Redis (el mismo `bank-redis`), 10 por segundo con ráfaga de 20 (`RATE_LIMIT_REPLENISH` y `RATE_LIMIT_BURST`). Al agotarlo responde **429** en ProblemDetail con los headers `X-RateLimit-*`. Un usuario a ritmo normal no llega al límite; un script con muchas peticiones simultáneas sí. Si Redis no responde, el gateway deja pasar las peticiones (falla abierta) y lo registra en el log.
- **Timeouts y circuit breaker** (Resilience4j): conecta en 1 s y espera la respuesta 3 s. Si el servicio está caído, lento o responde 5xx, el gateway devuelve **503** en ProblemDetail sin detalles internos. Con 50 % de fallos en las últimas 10 llamadas (mínimo 5) el circuito se **abre**: durante 10 s responde 503 al instante sin llamar al servicio, y luego prueba con 3 llamadas antes de cerrarse. Los 4xx (403, 404…) no cuentan como fallo. Estado: `curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/actuator/circuitbreakers`. Recién arrancado el gateway, mientras aún no tiene la lista de Eureka, el circuito puede abrirse unos segundos; se cierra solo.

Orden de arranque: Docker → config-server → discovery-server → customers-service → api-gateway.

### Pruebas
```bash
cd customers-service && ./gradlew test
cd config-server && ./gradlew test
cd discovery-server && ./gradlew test
cd api-gateway && ./gradlew test
```
No necesitan el config-server ni Eureka encendidos (las de customers-service leen `config-repo/` directamente y no se registran) ni credenciales de Azure (Cosmos está simulado), pero sí Docker encendido: Testcontainers levanta Redis para las pruebas de cache y un Keycloak con `infra/keycloak/bank-realm.json` para probar la seguridad con tokens reales (firma, `iss`, `aud`, roles y `oid`), tanto en customers-service como en el api-gateway (este último frente a un customers-service simulado).

## Gestión
- Jira: proyecto `BANK`
- Ramas: `feature/BANK-<n>-<descripcion>`
