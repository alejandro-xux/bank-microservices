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
- Checklist del PR completo, merge a `main` y ticket en **Done**.

## Convenciones
- Java 21, Gradle con toolchain.
- Paquete base: `com.alejandro.bank.<servicio>`.
