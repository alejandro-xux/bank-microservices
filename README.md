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
| accounts-service-quarkus | La misma cuenta en Quarkus 3.40 (E14, en migración): Quarkus REST + Hibernate Panache sobre la misma base, el mismo contrato. Ver abajo |
| notifications-service | Spring Kafka + PostgreSQL: avisos al cliente a partir de los eventos (sin API pública) |
| fraud-service | Quarkus 3.40 **nativo** (GraalVM/Mandrel) + Kafka + PostgreSQL: revisa cada transferencia completada (E14, sin API pública). Ver abajo |
| contracts | Contratos internos: gRPC (`customers.proto`) y eventos de Kafka (`transfer_events.proto`), con su código generado compartido por los servicios ([detalle](contracts/README.md)) |

## Entorno local
**En un comando** (Docker encendido y las credenciales de Cosmos en `~/.bank-microservices.env`, o en la ruta que indique `BANK_ENV_FILE`):

```bash
infra/start-local.sh    # Docker (Redis, PostgreSQL, Keycloak, Kafka, Mailpit, mainframe emulado) → config-server → discovery-server → customers-service → accounts-service → api-gateway
infra/stop-local.sh     # apaga los servicios (con --all también los contenedores de Docker)
```

Espera a que cada pieza esté sana antes de seguir, salta las que ya están corriendo y termina cuando el gateway ya enruta a customers-service (~1 minuto desde cero). Los logs de cada servicio quedan en `.local/logs/`. Las secciones siguientes explican cada pieza por separado.

**Dos modos (BANK-93).** El de arriba corre los servicios con `bootRun`: sirve para desarrollar, porque cambias código y reinicias un solo servicio. Con `--containers` los 6 servicios también corren en contenedores, con las imágenes de BANK-92, como correrán en producción:

```bash
infra/start-local.sh --containers   # construye las imágenes que falten o hayan cambiado y levanta todo (~2 min)
infra/stop-local.sh --containers    # apaga solo los 6 servicios; la infraestructura y sus datos siguen
infra/stop-local.sh --all           # todo, en cualquiera de los dos modos
```

Los dos modos usan los mismos puertos, así que no se mezclan: `--containers` se niega a arrancar si hay servicios con `bootRun`. En contenedores solo se publican el gateway (`:8080`) y Eureka (`:8761`). Los servicios se encuentran por la red de Docker (`postgres`, `kafka:9092`, `keycloak:8080`…), con las mismas variables que ya lee `config-repo/` (los valores están en `infra/docker-compose.yml`, perfil `app`). Las credenciales de Cosmos llegan del mismo `~/.bank-microservices.env`: sin ese archivo el perfil `app` no arranca. Keycloak firma todos los tokens con el emisor `http://localhost:8180` (`KC_HOSTNAME`), se pidan desde la Mac o desde la red de Docker. Sin eso, el token que notifications pide por la red interna sale con otro emisor, customers lo rechaza y no llegan los correos. La prueba de punta a punta (`tests/e2e`) pasa igual en los dos modos.

### Imágenes de los servicios (BANK-92)
Cada servicio tiene su `Dockerfile` en dos etapas: compila con el JDK 21 y Gradle, y la imagen final lleva solo el JRE 21 (`eclipse-temurin:21-jre-noble`, elegida en el spike BANK-91) y la aplicación separada en capas. Se construyen desde la raíz del repo, porque cada servicio compila `platform-bom` y `contracts` con `includeBuild`.

```bash
infra/build-images.sh                      # las 6 imágenes (bank-<servicio>:local) y su revisión
infra/build-images.sh accounts-service     # solo una
docker build -f accounts-service/Dockerfile -t bank-accounts-service:local .   # lo mismo, a mano
infra/scan-images.sh                       # Trivy: vulnerabilidades por severidad; falla con CRITICAL/HIGH que tengan arreglo
```

El script revisa cada imagen y falla si algo no cumple: corre con el usuario `10001` (no root), tiene `HEALTHCHECK` contra el actuator y no lleva credenciales en sus variables ni en sus capas (Cosmos y el secreto de notifications llegan al correr, por variables). La caché de Gradle queda en BuildKit: sin cambios una imagen se reconstruye en ~1 s, y con una clase cambiada en ~11 s, porque solo cambia la última capa (la aplicación, cientos de kB) y las dependencias (~130 MB) se reutilizan. La primera vez tarda unos 4 minutos, porque baja Gradle y las dependencias.

| Imagen | Comprimida |
|---|---|
| `bank-config-server:local` | 136 MB |
| `bank-discovery-server:local` | 149 MB |
| `bank-api-gateway:local` | 166 MB |
| `bank-notifications-service:local` | 183 MB |
| `bank-customers-service:local` | 188 MB |
| `bank-accounts-service:local` | 211 MB |

Unos 100 MB de cada una son el JRE y Ubuntu, que se comparten: en un registro o un nodo se guardan una sola vez.

**La JVM en el contenedor (BANK-94).**
- **Memoria.** Cada servicio tiene un límite de memoria en compose (`mem_limit`: config 384 MB, discovery, gateway y notifications 512 MB, customers y accounts 768 MB). El heap es el 60 % de ese límite, por `JAVA_TOOL_OPTIONS` en la imagen, que se puede cambiar al correr. Medido con carga: entre 180 y 490 MB por servicio, sin ningún OOMKilled.
- **Health check.** El `HEALTHCHECK` mira la readiness, que incluye la base o Cosmos según el servicio. La liveness no depende de nada externo.
- **Apagado ordenado.** Con `server.shutdown: graceful`, un `docker stop` deja terminar las peticiones en curso. Compose le da 30 s antes de cortarlo.

### accounts-service-quarkus (E14, BANK-99)
La migración de accounts-service a Quarkus, al lado de la versión Spring, que no se toca (estrategia *strangler*). Las dos usan la misma base `accounts`, la misma API, los mismos eventos y el mismo `.proto`. Solo Spring aplica las migraciones de Flyway; Quarkus valida al arrancar que sus entidades coincidan con las tablas, y si no coinciden no arranca.

- Puerto **8084**. Corre en el modo `--containers` (perfil `app`). El gateway le manda tráfico según los pesos del canary (abajo); por defecto, ninguno.
- Todo accounts migrado: consultas, apertura y depósitos (BANK-100), transferencias con la SAGA del core y el recuperador (BANK-101), y el outbox con su relay a Kafka y la limpieza (BANK-102). Tiene los mismos códigos, JSON, errores y eventos. Entre las dos versiones se coordinan por la base:
    - una `Idempotency-Key` usada en una sirve en la otra;
    - el recuperador (candado consultivo 79) y el relay (candado 69) corren en uno solo a la vez.
- Paridad (BANK-103): el mismo `transferencias.feature` y la misma E2E corren contra las dos versiones; `tests/parity/compare-errors.sh` compara sus errores campo por campo.
- Configuración: `accounts-service-quarkus/src/main/resources/application.properties` (lo que se fija al compilar) y `config-repo/accounts-service-quarkus.yml` (lo de cada entorno, con las mismas variables que accounts-service).
- Pruebas: `cd accounts-service-quarkus && ./gradlew test` (`@QuarkusTest` con PostgreSQL, Kafka y el core emulado de Testcontainers, y un customers falso por gRPC). La cobertura la mide `quarkus-jacoco`, que solo cuenta el código que corre dentro de la aplicación.
- Imagen: `infra/build-images.sh accounts-service-quarkus` (144 MB comprimida, frente a 211 MB de la versión Spring).
- **Versión nativa (BANK-109), para medir:** `accounts-service-quarkus/Dockerfile.native`, servicio `accounts-service-quarkus-native` del perfil `native` (sin tráfico del gateway). No necesitó ningún registro de reflexión a mano. Quarkus REST registra los DTO, el ProblemDetail es un `Map`, las tramas del core son texto y el código generado de Protobuf no usa reflexión. La prueba contra el binario es `src/native-test/AccountsNativeIT` y la corre `native.yml`.
- Mientras convivan las dos versiones, Sonar no cuenta como duplicado el código copiado (`sonar.cpd.exclusions`), y cada módulo tiene su propia cobertura (`sonar.modules`).

#### Canary: pasar el tráfico de Spring a Quarkus (runbook, BANK-104)
El gateway tiene dos rutas para `/api/accounts/**` y `/api/transfers/**` en el mismo grupo de pesos. Cada petición va a una sola versión, y la respuesta dice cuál la atendió en el header `X-Served-By` (`accounts-service` o `accounts-service-quarkus`). Cada versión tiene su propio circuito (`accounts`, `accounts-quarkus`) y su propio balde de rate limit.

**Avanzar** (100/0 → 90/10 → 0/100), sin reiniciar nada:

1. En `config-repo/api-gateway.yml`, descomentar el bloque `bank.canary.accounts` del final y poner los pesos (suman 100).
2. Aplicarlos con el token del asesor (el actuator del gateway es solo para ADMIN):
   ```bash
   ADMIN=$(curl -s -X POST http://localhost:8180/realms/bank/protocol/openid-connect/token \
     -d grant_type=password -d client_id=bank-cli -d username=asesor -d password=asesor-local | jq -r .access_token)
   curl -s -X POST -H "Authorization: Bearer $ADMIN" http://localhost:8080/actuator/refresh
   # ["bank.canary.accounts.quarkus-weight","bank.canary.accounts.spring-weight"]: los pesos nuevos ya rigen
   ```
3. Mirar unos minutos antes del paso siguiente (qué mirar, abajo).

**Volver atrás:** los pesos en 100 y 0, y el mismo `refresh`. Si el refresh no responde, el último recurso es reiniciar el gateway con `ACCOUNTS_SPRING_WEIGHT=100 ACCOUNTS_QUARKUS_WEIGHT=0`, las variables de arranque. Un peso de `config-repo` gana a la variable, así que antes hay que volver a comentar el bloque.

**Qué mirar:**
- **Reparto y errores por versión:**
  - `X-Served-By` de las respuestas;
  - una carga de ensayo: `python3 -I tests/canary/carga.py --segundos 300 --por-segundo 4 --salida carga.jsonl` y después `--resumen carga.jsonl --fases fases.txt`.
- **Circuito de cada versión:** `GET /actuator/circuitbreakers` (asesor). Si Quarkus se cae, solo su parte del tráfico recibe el 503 del circuito; la de Spring sigue igual. Ese 503 lo arma el gateway (plan B del circuito) y viene **sin** `X-Served-By`. En el ensayo, con Quarkus apagado y 90/10, el 11.5 % recibió 503 y el resto respondió bien.
- **El banco no crea ni pierde dinero:**
  - saldos + transferencias `PENDING` iguales antes y después;
  - `outbox` sin pendientes viejas (las dos versiones publican con el mismo relay coordinado).
- **Logs de las dos versiones:** `docker logs bank-accounts-service-quarkus` / `bank-accounts-service`.

### fraud-service (E14, BANK-107)
Servicio nuevo, escrito en Quarkus desde el principio y compilado a **binario nativo** (spike BANK-105). Lee las transferencias completadas de `bank.transfers.v1` con su propio grupo de consumidores, así no le quita eventos a notifications-service, y no toca accounts.

**Reglas de fraude (BANK-108).** Avisan, no frenan: el dinero ya se movió. Los umbrales están en `fraud.rules.*` (`application.properties`, que se cambian por entorno en `config-repo/fraud-service.yml`):

| Regla | Dispara cuando (valores por defecto) |
|---|---|
| `HIGH_AMOUNT` | 10 000 PEN o 3 000 USD, o desde 1 000 PEN / 300 USD si pasa 5 veces el promedio de la cuenta (con al menos 3 transferencias antes) |
| `VELOCITY` | más de 5 transferencias desde la misma cuenta en 10 minutos, contando la hora del evento |
| `NEW_DESTINATION_HIGH_AMOUNT` | desde 2 000 PEN o 600 USD a un destino al que la cuenta nunca transfirió |

- Cada evaluación queda en `fraud_review`: transferencia, reglas y resultado. Es también la historia de la cuenta que usan las reglas, y empieza con BANK-108.
- Una sospechosa publica `FraudSuspected` en `bank.fraud.v1` (`contracts/src/main/proto/fraud_events.proto`), sin datos personales y con un id derivado del evento: un aviso repetido se reconoce como el mismo.
- El mismo evento dos veces no da dos avisos: `processed_event` lo descarta antes de evaluar.
- Las reglas son dominio puro (`fraud-service/.../rules`) y se prueban sin Quarkus.

```bash
# Los avisos
docker exec bank-postgres psql -U fraud -d fraud -c "SELECT reviewed_at, transfer_id, amount, currency, rules FROM fraud_review WHERE suspicious ORDER BY reviewed_at DESC LIMIT 10"
```

- Puerto **8085**, sin API pública: solo el health está abierto, el resto pide token.
- Base propia `fraud`. Este servicio sí aplica sus migraciones (Flyway al arrancar). `processed_event` guarda cada evento revisado, así un duplicado de Kafka no se revisa dos veces. Guarda un resumen en JSON sin datos personales.
- Un mensaje que no se puede leer (sin el header `event-type`, o un `TransferCompleted` sin id) va a `bank.fraud.v1.DLT` sin detener el consumo. Los `TransferFailed` se ignoran.
- **Nativo en local:** perfil `native` del compose (no `app`), porque la imagen tarda unos 8 minutos y pide unos 6 GB a Docker:
  ```bash
  # con los servicios en contenedores (start-local.sh --containers)
  docker compose -f infra/docker-compose.yml --profile native up -d --build --wait fraud-service
  # con los servicios en bootRun: el config-server es el del host
  NATIVE_CONFIG_SERVER_URL=http://host.docker.internal:8888 docker compose -f infra/docker-compose.yml --profile native up -d --build --wait fraud-service
  ```
- **En JVM, para desarrollar:** `cd fraud-service && ./gradlew quarkusDev` (con el config-server de bootRun).
- Pruebas: `./gradlew test` (`@QuarkusTest` con PostgreSQL y Kafka de Testcontainers). Las mismas clases corren contra el binario con `@QuarkusIntegrationTest` (`src/native-test`, tarea `testNative`). Esa prueba solo corre en Linux: en la Mac el binario es para Linux. La corre el workflow `native.yml`, junto con la imagen (hadolint, usuario, HEALTHCHECK, Trivy), cuando cambian fraud-service, contracts o el BOM, en main, cada noche y a mano. No va en cada PR.
- La imagen es de tres etapas: Temurin arma las fuentes nativas, Mandrel 25 compila el binario y la imagen final es `ubi9-quarkus-micro-image`, sin JVM ni curl. Por eso el HEALTHCHECK usa bash `/dev/tcp`.

### Datos de prueba (BANK-75)
Si la base de accounts está vacía (primer arranque o después de `--all`), `start-local.sh` corre `infra/seed-local.sh`, que abre por la API, como un asesor, las cuentas con las que probamos:

| Número | Cliente | Moneda | Saldo inicial |
|---|---|---|---|
| `001-0000000001-6` | Ana | PEN | 1580.50 |
| `001-0000000002-4` | Ana | USD | 250.00 |
| `001-0000000003-2` | cliente destino (`08d38a3b-…`) | PEN | 370.00 |
| `001-0000000004-0` | Ana | USD | 0.00 |

```bash
infra/seed-local.sh --show                          # número → id (los ids cambian cada vez que la base empieza de cero)
infra/stop-local.sh --all && infra/start-local.sh   # volver a los datos de prueba
```

**Qué borra `stop-local.sh --all`**: ningún contenedor tiene volumen, así que se pierden las cuentas y su historial (transferencias, movimientos, outbox), los avisos registrados por notifications, los eventos y offsets de Kafka, la caché de Redis y los correos de Mailpit. Keycloak se recrea desde `infra/keycloak/bank-realm.json` y los clientes viven en Cosmos (Azure): esos no se pierden. El seed es idempotente: con las cuentas ya creadas no hace nada, y nunca mueve dinero en una base que ya tiene cuentas.

La infraestructura corre en Docker: Redis en el puerto **6380**, PostgreSQL en el **5433** y Kafka en el **9094** (los estándar 6379, 5432 y 9092 pueden estar ocupados por otros proyectos), Keycloak en el **8180** y Mailpit en el **1025** (SMTP) y el **8025** (web). Ninguno guarda datos al hacer `down`: la próxima vez arrancan vacíos.

```bash
docker compose -f infra/docker-compose.yml up -d   # levanta todo; kafka-init crea los topics y termina
docker compose -f infra/docker-compose.yml ps      # todos "healthy" (Keycloak tarda ~30 s); kafka-init no aparece porque ya terminó
docker compose -f infra/docker-compose.yml down    # los apaga
```

### Kafka (eventos, BANK-67)
Un solo nodo en modo KRaft (broker y controlador, sin ZooKeeper), `apache/kafka:4.3.1`. En Azure lo reemplaza Event Hubs (Standard o superior, endpoint Kafka en `:9093` con SASL_SSL) o Confluent Cloud; el código no cambia, solo la configuración.

- Desde la máquina (servicios con `bootRun`): `localhost:9094`. Desde otro contenedor: `kafka:9092`. Son dos listeners porque Kafka le dice al cliente a qué dirección volver, y `localhost` dentro de un contenedor es el propio contenedor.
- Los topics no los crea la app: los crea `infra/kafka/create-topics.sh` (contenedor `kafka-init`), como lo hará Terraform en Azure. La creación automática está apagada, así que publicar en un topic con un nombre mal escrito falla.

| Topic | Particiones | Para qué |
|---|---|---|
| `bank.transfers.v1` | 3 | Transferencias completadas (clave = cuenta origen, así se mantiene el orden por cuenta) |
| `bank.transfers.v1.DLT` | 3 | Mensajes que no se pudieron procesar |
| `bank.fraud.v1` | 3 | Transferencias sospechosas (fraud-service, BANK-108) |
| `bank.fraud.v1.DLT` | 3 | Lo que fraud-service no pudo procesar de `bank.transfers.v1` |

```bash
# Ver los topics y leer un topic desde el principio
docker exec bank-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --describe
docker exec bank-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
  --topic bank.transfers.v1 --from-beginning --property print.key=true
```

### core-banking-mock (mainframe emulado, BANK-76)
WireMock en el puerto **8089** que se comporta como un core bancario legado: recibe asientos contables en **tramas de largo fijo** (`POST /core/asientos`, 75 caracteres) y consultas (`POST /core/consultas`, 47) y responde igual (52). Recuerda cada asiento por id-op (BANK-90): la consulta dice qué pasó con **ese** asiento y `025 NF` si nunca llegó. El asiento decide por los céntimos del monto (`.13` rechazo, `.99` tarda 5 s pero registra, `.98` core caído). Formato, reglas y límites en [infra/core-banking-mock/README.md](infra/core-banking-mock/README.md).

### Mailpit (correos de prueba)
Recibe por SMTP en `localhost:1025` y muestra cada correo en http://localhost:8025. Guarda en memoria: se vacía al reiniciar. En Azure lo reemplaza un proveedor de correo real (por ejemplo, Azure Communication Services Email).

### Keycloak (identidad, solo local)
En Azure la identidad la da Microsoft Entra; Keycloak lo reemplaza en local y emite tokens con la misma forma (claims `roles`, `oid` y `aud`). La audiencia es `bank-api`: un mismo token sirve para toda la API detrás del gateway.

- Consola: http://localhost:8180/admin (usuario `admin` / `admin`), realm `bank`.
- El realm se importa al arrancar desde `infra/keycloak/bank-realm.json`. Los cambios hechos en la consola se pierden al recrear el contenedor; lo que deba quedar se edita en el JSON y se aplica con `docker compose -f infra/docker-compose.yml up -d --force-recreate keycloak`.

Usuarios de prueba (contraseñas solo locales, no son secretos):

| Usuario | Contraseña | Rol | `oid` |
|---|---|---|---|
| `ana` | `ana-local` | `CUSTOMER` | `4f8c2a1e-6b3d-4c9a-9e7f-2d5b8a1c3e01` |
| `asesor` | `asesor-local` | `ADMIN` | `7b2e9d4c-1a5f-4e8b-b3c6-9f0a2d4e6b02` |

Cuenta de servicio (BANK-71): el cliente confidencial `notifications-service` (secreto local `notifications-local`) pide su propio token con *client credentials*, sin usuario. Su token lleva solo el rol `NOTIFICATIONS` y la audiencia `bank-api`; en Azure es un app registration (o una identidad administrada) con ese app role.

```bash
curl -s -d grant_type=client_credentials -d client_id=notifications-service -d client_secret=notifications-local \
  http://localhost:8180/realms/bank/protocol/openid-connect/token
```

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

**gRPC interno** (BANK-61): además de REST en `:8081`, customers-service responde por gRPC en `:9091` (`GRPC_PORT`) con el contrato de [`contracts/customers.proto`](contracts/README.md). Lo usan accounts-service y notifications-service; la app y el gateway siguen con REST.

| Método | Quién | Respuesta |
|---|---|---|
| `GetCustomer(id)` | ADMIN | el resumen del cliente (`id`, `status`); no existe → `NOT_FOUND` |
| `GetMyCustomer()` | CUSTOMER | el cliente vinculado al token; sin vínculo → `PERMISSION_DENIED` |
| `GetContact(id)` | NOTIFICATIONS (cuenta de servicio de notifications-service) | nombre y correo principal para un aviso; no existe → `NOT_FOUND`. Ni el cliente ni el asesor pueden: el que no avisa no necesita el correo (BANK-71) |

- El JWT va en la metadata `authorization` y se valida con las mismas propiedades que REST (iss, `aud = bank-api`, roles). Sin token o con uno inválido → `UNAUTHENTICATED`; sin el rol → `PERMISSION_DENIED`.
- `grpc.health.v1.Health` responde sin token, para las probes. *Reflection* solo se activa con `GRPC_REFLECTION_ENABLED=true`, para explorar en local con grpcurl.
- Si el cliente corta (deadline vencido), el servidor cancela la consulta en curso.
- **Cache del vínculo** (BANK-65): `/customers/me` y `GetMyCustomer` guardan `identity:v1:{oid}` → id del cliente en Redis (TTL 10 min). Así se evita la consulta cross-partition a Cosmos (~250 ms → ~3 ms). Vincular o desvincular borra esa entrada en el mismo momento, y además la ficha manda: si ya no está vinculada a ese oid, se consulta Cosmos. Un oid sin vínculo no se guarda.
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

Orden de arranque: Docker → config-server → discovery-server → customers-service → accounts-service → notifications-service → api-gateway.

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
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' -d '{"amount":"150.00"}'
# 201 con el movimiento CREDIT; balanceAfter es el saldo nuevo
```

- En una sola transacción: bloquea la fila de la cuenta (`SELECT … FOR UPDATE`), suma el monto y registra el movimiento. Si algo falla, no cambia ni el saldo ni el historial, y dos depósitos simultáneos en la misma cuenta se aplican uno después del otro.
- `amount` puede ir como texto (`"150.00"`) o como número (`150.00`): en los dos casos se lee exacto, sin `double`. Debe ser positivo, con 2 decimales como máximo (400 si no). Cuenta inexistente → 404; bloqueada o cerrada → 422; cliente (CUSTOMER) → 403.
- **Idempotente** (BANK-64): `Idempotency-Key` es obligatoria (UUID). Un reintento con la misma clave y los mismos datos devuelve el mismo movimiento con `Idempotent-Replayed: true`, sin volver a sumar. La misma clave con otra cuenta u otro monto → 409. Un doble clic simultáneo suma una sola vez (`UNIQUE` en `movement.idempotency_key`, migración V2). Un depósito rechazado (404, 422) no consume la clave.

**Transferir** (cliente desde sus cuentas, asesor desde cualquiera). El destino va por su número y la `Idempotency-Key` es un UUID nuevo por cada transferencia, el mismo en cada reintento:

```bash
curl -i -X POST http://localhost:8082/transfers -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  -d '{"fromAccountId":"<id de mi cuenta>","toAccountNumber":"001-0000000003-2","amount":"100.00"}'
```

| Caso | Respuesta |
|---|---|
| El core registró el asiento | 201, `status: COMPLETED` |
| El core no respondió a tiempo (o está caído) | 202, `status: PENDING`: el débito está hecho y se resuelve sola |
| El core la rechazó | 422 con `transferId`, `transferStatus: FAILED` y `reason` (código del core): el dinero ya volvió a la cuenta |
| Reintento con la misma clave y los mismos datos | la misma transferencia en su estado de ahora + `Idempotent-Replayed: true` (no mueve dinero otra vez) |
| Misma clave con otros datos | 409 |
| Falta la clave, monto inválido, misma cuenta o número de destino mal tipeado (dígito verificador) | 400 |
| Cuenta origen ajena o inexistente (cliente) | 403 |
| Fondos insuficientes, monedas distintas, destino inexistente o no activo | 422 (la clave queda libre para reintentar) |

`GET /transfers/{id}`: en qué quedó (el dueño de la cuenta origen o el asesor; un cliente recibe el mismo 403 si es de otro o si no existe).

**SAGA con el core bancario** (BANK-78, spike BANK-74): una transferencia queda completa solo cuando el core registra el asiento contable.

```
POST /transfers
  ├─ T1: candados · débito en el origen · transfer PENDING (con su id-op para el core)
  ├─ asiento en el core, fuera de toda transacción
  │     ├─ registrado → T2: crédito en el destino · COMPLETED · outbox TransferCompleted   → 201
  │     ├─ rechazado  → T2: REVERSAL en el origen · FAILED                                 → 422
  │     └─ sin respuesta → sigue PENDING                                                   → 202
```

- **Un timeout no es un rechazo**: el core pudo haber registrado el asiento aunque la respuesta no llegara. Devolver el dinero en ese momento lo dejaría en dos lugares a la vez.
- **El destino no recibe nada hasta que el core confirma**, y la compensación es un movimiento `REVERSAL`: el débito no se borra, el historial cuenta que el dinero salió y volvió.
- **Una transferencia se resuelve una sola vez**: la transición es `UPDATE … WHERE status = 'PENDING'` y solo quien la gana mueve dinero. Sin esa condición, dos procesos acreditaron dos veces.
- **El dinero en tránsito existe**: con pendientes, la suma de saldos baja. La regla que vigilan las pruebas es **saldos + pendientes = constante**; mirar cuenta por cuenta no alcanza, porque en el caso del crédito doble cada cuenta seguía cuadrando con sus movimientos.
- Medido en local: `POST /transfers` pasó de p50 6,7–7,9 ms a 13,4–14,7 ms (la segunda transacción con candados y outbox, el asiento por el adaptador y la relectura); justo después de reiniciar el servicio, 25–27 ms.
- Las dos cuentas se bloquean (`FOR UPDATE`) siempre en el mismo orden, el id menor primero. Así dos transferencias cruzadas (A→B y B→A) no se bloquean entre sí: con el orden de la petición, PostgreSQL detecta *deadlock detected*.
- La clave se reclama con un `INSERT` antes de tocar los saldos. Un doble clic simultáneo choca con el `UNIQUE` y responde la transferencia del primero.

**Recuperador de la SAGA** (BANK-79): ninguna transferencia queda `PENDING` para siempre, ni por un timeout del core ni porque accounts-service se cayó a mitad de camino. Cada 5 s toma las pendientes con más de 10 s, le pregunta al core por su id-op y la termina como lo habría hecho el orquestador:

| El core dice | El recuperador |
|---|---|
| registrado | T2: crédito · `COMPLETED` · `TransferCompleted` |
| rechazado | T2: `REVERSAL` · `FAILED` |
| no existe (nunca llegó) | reenvía el mismo asiento (mismo id-op, el core no lo duplica) y aplica la respuesta |
| sin respuesta | nada: la próxima vuelta |

- **Nunca compensa a ciegas.** Si sigue pendiente después de 30 min, se marca `needs_review` (migración V5) y queda un log `ERROR` para revisarla con el core. El dinero no se devuelve solo, porque el core pudo haberla registrado.
- **Un solo recuperador a la vez**: `pg_try_advisory_xact_lock(79)`, como el relay del outbox (que usa el 69). Si dos instancias corrieran juntas, la transición `WHERE status = 'PENDING'` igual impide acreditar dos veces; el candado evita preguntarle lo mismo dos veces al core.
- Cada T2 se confirma en su propia transacción (`REQUIRES_NEW`). La del recuperador solo sostiene el candado.
- Configuración: `bank.saga.recovery.*` en `config-repo/accounts-service.yml` (`interval-ms`, `min-age`, `review-after`, `batch-size`, `enabled`). Las pruebas lo apagan y lo llaman a mano.
- Desde BANK-90 el mock recuerda los asientos. Una caída antes del asiento se prueba con un monto normal: la consulta dice "no existe", el recuperador reenvía y la transferencia termina COMPLETED.

**Core bancario** (BANK-77, Anti-Corruption Layer): accounts-service habla con el mainframe emulado (`CORE_BANKING_URL`, por defecto `http://localhost:8089`, plazo `CORE_BANKING_TIMEOUT` de 2 s) sin que su formato entre al dominio. El dominio solo ve `CoreBanking` (asiento y consulta) con números de cuenta y montos propios; `corebanking/mainframe` arma y lee las tramas de largo fijo y traduce cuentas (`001-0000000001-6` ↔ `00100000000016`) y montos (`100.00` ↔ céntimos). Un timeout, un core caído o una respuesta ilegible dan **desconocido**, nunca rechazo: el core pudo haber registrado el asiento.

**Evento de cada transferencia** (BANK-69, Transactional Outbox): la transacción que completa la transferencia (T2 desde BANK-78) guarda `TransferCompleted` (`contracts/transfer_events.proto`) en la tabla `outbox` (migración V3). La que la compensa guarda `TransferFailed` (BANK-80), con solo el lado del que envía. Si hay ROLLBACK no queda evento; si hay COMMIT, el evento existe sí o sí. Un relay lo publica después en `bank.transfers.v1`, con la cuenta origen como clave. Las filas publicadas se borran pasados 7 días (BANK-81, `bank.outbox.cleanup.*`): cada hora, de a 1000, nunca una pendiente, y sin frenar al relay.

```
T2 de una transferencia completada ─▶ crédito + fila en outbox (COMMIT)
relay (cada 500 ms, lotes de 100) ─▶ Kafka (acks=all) ─▶ marca la fila como publicada
```

- **Kafka caído no frena las transferencias**: responden 201 igual, y el evento sale cuando Kafka vuelve. El productor se rinde a los 5-10 s (no a los 60 s por defecto) para no dejar una conexión de PostgreSQL tomada.
- **Un solo relay activo** aunque haya varias instancias: un candado consultivo de PostgreSQL (`pg_try_advisory_xact_lock`). Con `FOR UPDATE SKIP LOCKED`, dos relays desordenarían los eventos de una misma cuenta; sin candado, los duplicarían. El orden lo da la columna `seq`, que se toma con las cuentas ya bloqueadas.
- **Al menos una vez, no exactamente una**: si el relay muere entre publicar y marcar, o un envío vence mientras el mensaje ya estaba en camino, el evento sale dos veces con el mismo `event_id`. El consumidor descarta por ese id.
- Medido en local: la transferencia pasó de p50 5,8-6,3 ms a 6,1-7,2 ms; del COMMIT a Kafka, p50 273 ms.
- `KAFKA_BOOTSTRAP_SERVERS` (por defecto `localhost:9094`).

**Historial de movimientos**: `GET /accounts/{id}/movements?page=0&size=20` (`size` como máximo 50), del más reciente al más antiguo, con la misma regla del dueño que `GET /accounts/{id}`. Cada línea trae el tipo, el monto, el saldo después y, si vino de una transferencia, la otra cuenta enmascarada (`001-******0003-2`).

- Sale en un número fijo de consultas (la cuenta, la página y el total), sin importar el tamaño de la página: la otra cuenta viene del mismo `LEFT JOIN`, no de una consulta por movimiento (N+1). La prueba lo demuestra contando las sentencias con las estadísticas de Hibernate: una versión N+1 hace 13 con `size=5` y 51 con `size=25`; esta hace 3 en los dos casos.
- Usa el índice `movement(account_id, created_at DESC)`.

- El vínculo entre cuenta de acceso y cliente no se copia en accounts-service: se pregunta cada vez, porque el asesor puede cambiarlo (BANK-48).
- Si customers-service no responde, las consultas de un cliente devuelven 503 (las del asesor no dependen de él). Cuando vuelve, accounts-service lo encuentra en un par de segundos: el canal gRPC apunta a una dirección fija y se reconecta solo. Con Feign y Eureka tardaba ~1 minuto, por la copia de Eureka y la caché del load balancer.

### notifications-service
Avisa al cliente de lo que pasa en sus cuentas, a partir de los eventos de Kafka (E7). No tiene API pública ni ruta en el gateway: solo actuator en el puerto **8083** (`/actuator/health`).

```bash
cd notifications-service
./gradlew bootRun     # o infra/start-local.sh, que lo levanta después de accounts-service
```

- Consume `bank.transfers.v1` con el consumer group **`notifications-service`**. La primera vez que arranca el grupo lee el topic desde el principio, así que un evento publicado antes de desplegar el servicio también se avisa. Cada grupo lleva su propio offset: otro consumidor del mismo topic (fraud-service) no le quita mensajes.
- Su propia base `notifications` en el PostgreSQL local (usuario `notifications`, que no puede entrar a la de accounts); esquema con Flyway.
- Si Kafka todavía no arrancó, el servicio queda sano igual (la readiness solo incluye la base) y el consumidor reintenta solo: los eventos esperan en el topic.
- **Correo de cada transferencia** (BANK-72): "Enviaste" al dueño de la cuenta origen y "Recibiste" al de la destino (o un solo "Moviste… entre tus cuentas" si son del mismo cliente), con los números enmascarados (`001-******0003-2`) y la hora de Lima. Por SMTP: Mailpit en local (míralos en http://localhost:8025), un proveedor real en Azure (`MAIL_HOST`, `MAIL_PORT`, `MAIL_FROM`).
- **Correo de una transferencia rechazada** (BANK-80, evento `TransferFailed`): solo al que envía, "No pudimos completar tu transferencia de X; te devolvimos el dinero", con el código del core. El que iba a recibir no recibe nada, porque nunca le llegó dinero.
- **El tipo de evento lo dice el header `event-type`**, nunca el contenido. `TransferCompleted` y `TransferFailed` usan los mismos números de campo, así que leer uno como el otro da un error o un evento con los datos corridos. Un tipo que este servicio no conoce (o sin header) **se salta**: no es un error ni va al DLT, es un evento para otro consumidor. Si no se saltara, bloquearía su partición.
- **Un solo correo por destinatario** aunque el evento llegue dos veces o a dos instancias a la vez: cada aviso se *reclama* con un `INSERT … ON CONFLICT DO NOTHING` en `sent_notification` (clave `event_id` + destinatario) **en la misma transacción que el envío**. Si el SMTP falla, el reclamo se deshace y el reintento lo manda; si otra instancia lo está mandando, esta espera en la clave y después no manda nada. `processed_event` marca el evento completo para descartar un duplicado sin volver a pedir contactos.
- Un evento sin `event_id` válido, sin monto o sin clientes es inválido (proto3 lee un mensaje vacío sin error). Un cliente inexistente o sin correo queda como "no enviado" y no se reintenta.
- Los timeouts de SMTP están en 5 s: por defecto JavaMail espera para siempre a un servidor que no responde.
- **Si el correo o customers-service caen, no se pierde ningún aviso** (BANK-73):

| Qué falla | Qué pasa |
|---|---|
| Correo, `GetContact` o Keycloak (pasajero) | Se reintenta **sin límite**, con espera creciente de 1 s a 8 s |
| Mensaje ilegible o inválido (no se va a arreglar) | Al topic `bank.transfers.v1.DLT` enseguida, con el motivo en los headers; los que vienen detrás se procesan |
| Cualquier otro error (de código) | 3 reintentos y al DLT |

- Un **circuit breaker** (Resilience4j) sobre la entrega: con 3 de 5 fallas se abre y **pausa al consumidor** (los eventos esperan en Kafka sin gastar intentos); a los 10 s prueba con un aviso y, si sale, sigue donde quedó. Su estado se ve en `/actuator/health` → `deliveryCircuit` (`state`, `consumerPaused`), fuera de la readiness: un correo caído no saca al servicio del tráfico.
- Sin esta configuración, Spring Kafka hace 10 intentos seguidos y **salta** el mensaje con solo un `ERROR` en el log: en la prueba, una caída de ~15 s perdió los 8 avisos. Con "3 reintentos y al DLT", una caída de un minuto mandó avisos al DLT.
- Ver qué hay en el DLT:

```bash
docker exec bank-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
  --topic bank.transfers.v1.DLT --from-beginning --property print.headers=true
```
- **Contacto del cliente** (BANK-71): el evento no trae datos personales, así que pide nombre y correo a customers-service por gRPC (`GetContact`) con su **propio token** (*client credentials* contra Keycloak, rol `NOTIFICATIONS`). El token se guarda y se pide uno nuevo solo cuando está por vencer (5 llamadas → 1 token). Cliente inexistente → no se avisa; customers-service o Keycloak caídos → error que se reintenta. Plazo de 2 s por llamada (`CUSTOMERS_DEADLINE`).
- Necesita `NOTIFICATIONS_CLIENT_SECRET` (config-repo no tiene valor por defecto; `start-local.sh` pone el local). En Azure: `AUTH_TOKEN_URI` y `AUTH_CLIENT_SCOPE` (`api://bank-api/.default`) de Entra.
- Sin API: Spring Security solo deja pasar `/actuator/health` y `/actuator/info`.
- Variables: `KAFKA_BOOTSTRAP_SERVERS` (por defecto `localhost:9094`), `CUSTOMERS_GRPC_ADDRESS` (por defecto `static://localhost:9091`) y `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`.
- Ver el avance del grupo (lag = eventos que le faltan leer):

```bash
docker exec bank-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --describe --group notifications-service
```

### Pruebas
```bash
cd customers-service && ./gradlew test
cd config-server && ./gradlew test
cd discovery-server && ./gradlew test
cd api-gateway && ./gradlew test
cd accounts-service && ./gradlew test
cd notifications-service && ./gradlew test
cd contracts && ./gradlew test        # compatibilidad del contrato en el cable
```

Para medir la latencia de la comunicación accounts → customers con el entorno arriba: `cd tests/perf && ./gradlew run` ([detalle](tests/perf/README.md)).

Prueba de punta a punta con el entorno arriba (login real, gateway, mainframe, Kafka y correos): `cd tests/e2e && ./gradlew test`, ~30 s ([detalle](tests/e2e/README.md), BANK-88).
No necesitan el config-server ni Eureka encendidos (las de customers-service leen `config-repo/` directamente y no se registran) ni credenciales de Azure (Cosmos está simulado), pero sí Docker encendido: Testcontainers levanta Redis para las pruebas de cache y un Keycloak con `infra/keycloak/bank-realm.json` para probar la seguridad con tokens reales (firma, `iss`, `aud`, roles y `oid`), tanto en customers-service como en el api-gateway (este último frente a un customers-service simulado). En accounts-service levanta un PostgreSQL real: se prueban las migraciones, la validación del esquema y sus restricciones; el customers-service simulado es un servidor gRPC en memoria con el mismo contrato.

### Escenarios de negocio (BDD con Cucumber, BANK-87)
Las reglas de una transferencia están escritas en español en `accounts-service/src/test/resources/features/transferencias.feature`: el core registra, fondos insuficientes, monedas distintas, el core rechaza (REVERSAL), el core no responde (pendiente que termina el recuperador). Cada escenario corre contra la API real con PostgreSQL, Kafka y el mainframe emulado, y comprueba al final que el banco no creó ni perdió dinero. Son parte de `./gradlew test` y de la CI.

```bash
cd accounts-service
./gradlew test --tests '*TransferenciasBddTest'   # solo los escenarios
open build/reports/cucumber/transferencias.html   # el reporte: cada paso en verde o en rojo
```

Para un escenario nuevo, se escribe el texto en el `.feature` y, si usa una frase nueva, su paso en `bdd/TransferenciaSteps.java`. Los montos se escriben como en la API (`104.13`).

### CI (GitHub Actions, BANK-83)
Cada PR a `main` corre `.github/workflows/ci.yml`: las pruebas de los 7 módulos en paralelo, en una máquina Linux de GitHub, con la caché de Gradle. Tarda unos 3 minutos. **No se puede mergear si el check `ci-ok` no está en verde**: es lo que exige la ruleset `protect-main`.

- **Por qué falló:** en el PR, *Checks → ci* → el job del módulo en rojo. El resumen dice cuántas pruebas corrieron, cuáles fallaron y con qué mensaje (lo arma `.github/scripts/test-summary.py` leyendo los XML). Los reportes HTML completos quedan como artifact `reportes-<módulo>` por 7 días.
- **Pasa en tu Mac y falla en la CI:** la CI corre en Linux, y hay diferencias reales. La primera que encontró: allí `Instant.now()` tiene nanosegundos y PostgreSQL guarda microsegundos, así que toda fecha que se guarda pasa por `Timestamps.now()`.
- Un push nuevo al mismo PR cancela la corrida anterior. En `main` cada merge se prueba completo.

### Jenkins (BANK-89)
El `Jenkinsfile` de la raíz hace lo mismo que `ci.yml`: los 7 módulos en paralelo (`matrix`, cada celda en su agente), JUnit y cobertura publicados, gitleaks con canario, buf y un análisis de Sonar del repo entero. El token de Sonar es la credencial `sonar-token` de Jenkins. Con el parámetro `SONAR=false` se salta esa etapa. Se probó en un Jenkins temporal en Docker: 327 pruebas en verde, igual que en GitHub. La comparación entre las dos herramientas está en el documento de arquitectura.

### Calidad (SonarCloud, BANK-84)
Cuando las pruebas pasan, el job `sonar` analiza **el repo entero en un solo proyecto** ([alejandro-xux_bank-microservices](https://sonarcloud.io/project/overview?id=alejandro-xux_bank-microservices)). Usa las clases compiladas y la cobertura de JaCoCo que dejó cada módulo. `ci-ok` exige que pase el **Quality Gate "Sonar way"**, que mira solo el **código nuevo** del PR:

| Condición sobre el código nuevo | Umbral |
|---|---|
| Cobertura | ≥ 80 % |
| Líneas duplicadas | ≤ 3 % |
| Confiabilidad (bugs) | calificación A |
| Seguridad (vulnerabilidades) | calificación A |
| Mantenibilidad (deuda técnica de los code smells) | calificación A |
| Security hotspots revisados | 100 % |

- En el PR, SonarCloud deja un comentario con el resultado y el enlace a cada hallazgo.
- La cobertura de un módulo en local: `./gradlew test` deja el reporte en `build/reports/jacoco/test/html/index.html` (JaCoCo está en `gradle/quality.gradle`, que aplican todos los módulos).
- El plan gratis no permite un gate propio. Con pocas líneas nuevas (menos de 20), SonarCloud no aplica las condiciones de cobertura ni de duplicación.
- No corre en PRs de forks ni de Dependabot, porque GitHub no les pasa el secret `SONAR_TOKEN`. Ahí `ci-ok` acepta que Sonar quede sin correr.

## Gestión
- Jira: proyecto `BANK`
- Ramas: `feature/BANK-<n>-<descripcion>`
