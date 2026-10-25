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
| contracts | Contratos gRPC internos (`customers.proto`) y su código generado, compartido por los servicios ([detalle](contracts/README.md)) |

## Entorno local
**En un comando** (Docker encendido y las credenciales de Cosmos en `~/.bank-microservices.env`, o en la ruta que indique `BANK_ENV_FILE`):

```bash
infra/start-local.sh    # Redis + PostgreSQL + Keycloak → config-server → discovery-server → customers-service → accounts-service → api-gateway
infra/stop-local.sh     # apaga los servicios (con --all también Redis, PostgreSQL y Keycloak)
```

Espera a que cada pieza esté sana antes de seguir, salta las que ya están corriendo y termina cuando el gateway ya enruta a customers-service (~1 minuto desde cero). Los logs de cada servicio quedan en `.local/logs/`. Las secciones siguientes explican cada pieza por separado.

Redis, PostgreSQL y Keycloak corren en Docker: Redis en el puerto **6380** y PostgreSQL en el **5433** (el 6379 y el 5432 pueden estar ocupados por otros proyectos), y Keycloak en el **8180**.

```bash
docker compose -f infra/docker-compose.yml up -d   # levanta Redis, PostgreSQL y Keycloak
docker compose -f infra/docker-compose.yml ps      # los tres deben decir "healthy" (Keycloak tarda ~30 s)
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

**gRPC interno** (BANK-61): además de REST en `:8081`, customers-service responde por gRPC en `:9091` (`GRPC_PORT`) con el contrato de [`contracts/customers.proto`](contracts/README.md). Lo usa accounts-service; la app y el gateway siguen con REST.

| Método | Quién | Respuesta |
|---|---|---|
| `GetCustomer(id)` | ADMIN | el resumen del cliente (`id`, `status`); no existe → `NOT_FOUND` |
| `GetMyCustomer()` | CUSTOMER | el cliente vinculado al token; sin vínculo → `PERMISSION_DENIED` |

- El JWT va en la metadata `authorization` y se valida con las mismas propiedades que REST (iss, `aud = bank-api`, roles). Sin token o con uno inválido → `UNAUTHENTICATED`; sin el rol → `PERMISSION_DENIED`.
- `grpc.health.v1.Health` responde sin token, para las probes. *Reflection* solo se activa con `GRPC_REFLECTION_ENABLED=true`, para explorar en local con grpcurl.
- Si el cliente corta (deadline vencido), el servidor cancela la consulta en curso.
- La primera llamada después de arrancar puede tardar varios segundos: el cliente de Cosmos se inicia en ~6 s y las claves de Keycloak se descargan con el primer token.

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

- **Ruta de accounts-service** (BANK-58): `/api/accounts/**` y `/api/transfers/**` → `lb://accounts-service` (o `ACCOUNTS_SERVICE_URI`), sin el prefijo `/api`. Tiene el mismo límite por usuario (cada ruta con su propio balde: agotar el de customers no bloquea las transferencias) y su propio circuito `accounts`, independiente del de customers. Ese circuito **no cuenta el 503**: accounts lo responde a propósito cuando customers-service no está, y contarlo abriría el circuito de accounts por culpa de otro servicio. `Idempotency-Key` e `Idempotent-Replayed` pasan tal cual.
- Una transferencia que tarda más de 3 s recibe 503 del gateway aunque se haya hecho. Por eso la app debe **reintentar con la misma `Idempotency-Key`**: recibirá la transferencia real (201 + `Idempotent-Replayed`) sin que se cobre dos veces.

Orden de arranque: Docker → config-server → discovery-server → customers-service → accounts-service → api-gateway.

### accounts-service
Cuentas, saldos y transferencias, en el **puerto 8082**. Spring MVC con hilos virtuales, JPA y PostgreSQL (`bank-postgres`, base y usuario `accounts`; ver `infra/postgres/init.sql`). Necesita el config-server y PostgreSQL arriba. La configuración está en `config-repo/accounts-service.yml`; en Azure la base se define con `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME` y `DB_PASSWORD` (por defecto, el contenedor local).

```bash
cd accounts-service
./gradlew bootRun
curl http://localhost:8082/actuator/health   # "db": UP
```

- Las tablas las crea **Flyway** (`src/main/resources/db/migration/`) y Hibernate solo valida que las entidades coincidan (`ddl-auto: validate`): si no coinciden, el servicio no arranca. Un cambio de esquema es siempre un archivo `V2__…sql` nuevo; nunca se edita una migración ya aplicada.
- La base impide lo que no debe pasar aunque el código falle: saldo negativo, `Idempotency-Key` repetida y transferencia a la misma cuenta.
- Valida el token igual que customers-service (`aud = bank-api`), con 401 y 403 en ProblemDetail. `/actuator/health` es público y la readiness incluye la base.

**Abrir una cuenta** (solo asesor): accounts-service consulta a customers-service con el mismo token antes de escribir nada.

```bash
TOKEN=$(curl -s -X POST http://localhost:8180/realms/bank/protocol/openid-connect/token \
  -d grant_type=password -d client_id=bank-cli -d username=asesor -d password=asesor-local \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")
curl -i -X POST http://localhost:8082/accounts -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"customerId":"c15a442d-5146-471e-8804-746f4b42253b","type":"AHORROS","currency":"PEN"}'
# 201 + Location, número 001-NNNNNNNNNN-D (correlativo + dígito Luhn), saldo "0.00" (texto), ACTIVE
```

| Caso | Respuesta |
|---|---|
| Token de cliente (CUSTOMER) | 403 |
| Campos inválidos o moneda que no existe | 400 |
| El cliente no existe o está bloqueado | 422 |
| customers-service caído, con error o más lento que 2 s | 503, sin abrir la cuenta |

- La llamada a customers-service es por **gRPC** (BANK-62), con el contrato de `contracts/customers.proto`. Va a la dirección `CUSTOMERS_GRPC_ADDRESS` (por defecto `static://localhost:9091`, sin Eureka), reenvía el `Authorization` en la metadata (token relay) y tiene un plazo de 2 s por llamada (`CUSTOMERS_DEADLINE`). `NOT_FOUND` → 422, `PERMISSION_DENIED` en `/me` → 403, todo lo demás (caído, plazo vencido…) → 503.
- Swagger: http://localhost:8082/swagger-ui.html, con el mismo botón **Authorize** que customers-service (`asesor` para abrir cuentas, `ana` para ver las suyas).

**Consultar cuentas**: el saldo viaja como texto con 2 decimales (`"1500.50"`), así ningún cliente JSON lo convierte en `double`.

| Endpoint | Quién | Respuesta |
|---|---|---|
| `GET /accounts/me` | CUSTOMER | Sus cuentas. Para saber quién es, accounts-service llama a customers-service `GET /customers/me` con el mismo token; sin cliente vinculado → 403 |
| `GET /accounts/{id}` | ADMIN | Cualquier cuenta, o 404 |
| `GET /accounts/{id}` | CUSTOMER | Solo las suyas; una ajena y una inexistente responden **el mismo 403** (no revela qué ids existen) |
| `GET /accounts?customerId=` | ADMIN | Las cuentas de ese cliente (vacía si no tiene) |

**Depósito en ventanilla** (solo asesor), en la moneda de la cuenta:

```bash
curl -X POST http://localhost:8082/accounts/{id}/deposits -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"amount":"150.00"}'
# 201 con el movimiento CREDIT; balanceAfter es el saldo nuevo
```

- En una sola transacción: bloquea la fila de la cuenta (`SELECT … FOR UPDATE`), suma el monto y registra el movimiento. Si algo falla, no cambia ni el saldo ni el historial, y dos depósitos simultáneos en la misma cuenta se aplican uno después del otro.
- `amount` puede ir como texto (`"150.00"`) o como número (`150.00`): en los dos casos se lee exacto, sin `double`. Debe ser positivo, con 2 decimales como máximo (400 si no). Cuenta inexistente → 404; bloqueada o cerrada → 422; cliente (CUSTOMER) → 403.
- Por ahora un doble clic registra dos depósitos: la `Idempotency-Key` llega con las transferencias (BANK-56).

**Transferir** (cliente desde sus cuentas, asesor desde cualquiera). El destino va por su número y la `Idempotency-Key` es un UUID nuevo por cada transferencia, el mismo en cada reintento:

```bash
curl -i -X POST http://localhost:8082/transfers -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"fromAccountId":"<id de mi cuenta>","toAccountNumber":"001-0000000003-2","amount":"100.00"}'
```

| Caso | Respuesta |
|---|---|
| Transferencia hecha | 201 |
| Reintento con la misma clave y los mismos datos | 201 con la misma transferencia + `Idempotent-Replayed: true` (no mueve dinero otra vez) |
| Misma clave con otros datos | 409 |
| Falta la clave, monto inválido, misma cuenta o número de destino mal tipeado (dígito verificador) | 400 |
| Cuenta origen ajena o inexistente (cliente) | 403 |
| Fondos insuficientes, monedas distintas, destino inexistente o no activo | 422 (la clave queda libre para reintentar) |

- Todo en una transacción: el registro `transfer`, los dos saldos y los dos movimientos (`DEBIT` y `CREDIT`), o nada.
- Las dos cuentas se bloquean (`FOR UPDATE`) siempre en el mismo orden, el id menor primero. Así dos transferencias cruzadas (A→B y B→A) no se bloquean entre sí: con el orden de la petición, PostgreSQL detecta *deadlock detected*.
- La clave se reclama con un `INSERT` antes de tocar los saldos. Un doble clic simultáneo choca con el `UNIQUE` y responde la transferencia del primero.

**Historial de movimientos**: `GET /accounts/{id}/movements?page=0&size=20` (`size` como máximo 50), del más reciente al más antiguo, con la misma regla del dueño que `GET /accounts/{id}`. Cada línea trae el tipo, el monto, el saldo después y, si vino de una transferencia, la otra cuenta enmascarada (`001-******0003-2`).

- Sale en un número fijo de consultas (la cuenta, la página y el total), sin importar el tamaño de la página: la otra cuenta viene del mismo `LEFT JOIN`, no de una consulta por movimiento (N+1). La prueba lo demuestra contando las sentencias con las estadísticas de Hibernate: una versión N+1 hace 13 con `size=5` y 51 con `size=25`; esta hace 3 en los dos casos.
- Usa el índice `movement(account_id, created_at DESC)`.

- El vínculo entre cuenta de acceso y cliente no se copia en accounts-service: se pregunta cada vez, porque el asesor puede cambiarlo (BANK-48).
- Si customers-service no responde, las consultas de un cliente devuelven 503 (las del asesor no dependen de él). Cuando vuelve, accounts-service lo encuentra en un par de segundos: el canal gRPC apunta a una dirección fija y se reconecta solo. Con Feign y Eureka tardaba ~1 minuto, por la copia de Eureka y la caché del load balancer.

### Pruebas
```bash
cd customers-service && ./gradlew test
cd config-server && ./gradlew test
cd discovery-server && ./gradlew test
cd api-gateway && ./gradlew test
cd accounts-service && ./gradlew test
cd contracts && ./gradlew test        # compatibilidad del contrato en el cable
```
No necesitan el config-server ni Eureka encendidos (las de customers-service leen `config-repo/` directamente y no se registran) ni credenciales de Azure (Cosmos está simulado), pero sí Docker encendido: Testcontainers levanta Redis para las pruebas de cache y un Keycloak con `infra/keycloak/bank-realm.json` para probar la seguridad con tokens reales (firma, `iss`, `aud`, roles y `oid`), tanto en customers-service como en el api-gateway (este último frente a un customers-service simulado). En accounts-service levanta un PostgreSQL real: se prueban las migraciones, la validación del esquema y sus restricciones; el customers-service simulado es un servidor gRPC en memoria con el mismo contrato.

## Gestión
- Jira: proyecto `BANK`
- Ramas: `feature/BANK-<n>-<descripcion>`
