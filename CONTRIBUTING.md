# Guía de contribución

## Flujo de trabajo
1. Mover el ticket de Jira a **In Progress**.
2. Crear una rama desde `main`: `feature/BANK-<n>-<descripcion-corta>`
3. Hacer commits con la clave del ticket: `BANK-<n>: <qué cambia>`
4. Subir la rama y abrir un Pull Request con el título `BANK-<n>: <resumen>`.
5. Mover el ticket a **In Review**, completar el checklist del PR y hacer merge (squash).
6. Mover el ticket a **Done**.

`main` está protegida: no se permite push directo ni force push. Todo entra por Pull Request.

## Definition of Ready (antes de empezar una historia)
- Tiene criterios de aceptación claros (formato Dado / Cuando / Entonces).
- Tiene story points estimados.
- Sus dependencias están resueltas o identificadas.
- Está enlazada a su epic.

## Definition of Done (para cerrar una historia)
- Código en un PR enlazado al ticket.
- Pruebas unitarias (JUnit 5, Mockito, StepVerifier) con cobertura ≥ 80 % en la capa de servicio.
- Pruebas de integración si la historia toca base de datos o mensajería.
- OpenAPI actualizado.
- Errores en formato ProblemDetail (RFC 7807).
- Sin secretos en el código (credenciales solo por variables de entorno).
- CI en verde: el check `ci-ok` (BANK-83). Sin él GitHub no deja mergear. Si falla, se lee el resumen del job antes de reintentar: no se reintenta "a ver si pasa".
- Checklist del PR completo, merge a `main` y ticket en **Done**.

## Convenciones
- Java 21, Gradle con toolchain.
- Paquete base: `com.alejandro.bank.<servicio>`.

## Pruebas
Reglas que salieron de pruebas que fallaban a veces (Sprints 8 y 9). Valen para toda prueba que comparte Kafka, Mailpit o una base con otras.

- **Un consumer group por contexto de Spring.** Spring deja vivos en caché los contextos de otras clases de prueba. Con el mismo grupo, sus listeners se reparten las particiones y el evento de una prueba lo consume otra. Por eso `IntegrationTest` usa `group-id=notifications-test-${random.uuid}`.
- **Datos únicos por prueba**: montos, ids y números de cuenta propios de cada caso (por ejemplo `41.13`, `42.13`…). El topic y la bandeja de Mailpit son de toda la suite. Filtrar por un dato único es lo que permite contar "exactamente un correo".
- **Publicar como el productor real**: con los headers `event-type` y `event-id` del relay (`IntegrationTest.publish`). Sin el header, el consumidor no sabe qué mensaje es.
- **Esperar una condición, no un tiempo**: por ejemplo, hasta que el evento esté en `processed_event`. Si no se cumple dentro del plazo, la prueba falla; no sigue como si nada.
- **Los procesos de fondo apagados en las pruebas** (relay del outbox, recuperador de la SAGA, limpieza del outbox). La prueba los llama a mano, sin carreras con un `@Scheduled`.
- **Prueba al revés en cada garantía.** Se rompe a propósito la línea que la da (el filtro, el candado, el header…), se corre la prueba, se ve fallar donde se espera y se restaura. El resultado se lee en los XML de `build/test-results/test/` (cuántas pruebas, cuáles fallaron y con qué mensaje), no en la salida de `gradlew -q`: un "BUILD SUCCESSFUL" no dice qué pruebas corrieron.
- **Las fechas que se guardan, con `Timestamps.now()`**, nunca con `Instant.now()`: PostgreSQL guarda microsegundos y Linux da nanosegundos. En macOS el error no se ve, por eso se prueba con un `Clock` fijo.
- **Después de una prueba de dinero, la regla global**: saldos + pendientes = lo que había. Revisar cada cuenta por separado no alcanza.
- **`infra/start-local.sh` en segundo plano y sin pipe** (`… > archivo.log 2>&1`). Si la terminal o el pipe se cierran, se llevan los servicios que arrancó con `nohup`. Para reiniciar uno, detener solo ese proceso y volver a correr el script: levanta lo que falta y deja lo demás.
- **Antes de una verificación destructiva** (`stop-local.sh --all`, borrar datos locales), decir qué se pierde. Los contenedores no tienen volumen. Los datos de prueba se recrean con `infra/seed-local.sh`.
