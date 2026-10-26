# syntax=docker/dockerfile:1
# Imagen nativa de fraud-service (BANK-107, spike BANK-105 N3): un binario de GraalVM/Mandrel, sin JVM. Las mismas
# reglas que las demás imágenes (BANK-92): bases fijadas por digest, usuario 10001 y HEALTHCHECK. Desde la raíz del repo:
#   docker build -f fraud-service/Dockerfile -t bank-fraud-service:local .
# Tarda unos 8 minutos y necesita ~6 GB de memoria en Docker: por eso va en el perfil "native" del compose y en su
# propio workflow (native.yml), no en cada PR

# --- 1. Las fuentes nativas: Quarkus arma la aplicación y deja el jar y los argumentos de native-image ------------
FROM eclipse-temurin:21-jdk-noble@sha256:107215954359f1ab0be058a247d7c64ed6e69c71c1682d471d38ef751ec8e964 AS sources
WORKDIR /src
COPY gradle gradle
COPY platform-bom platform-bom
COPY contracts contracts
COPY fraud-service fraud-service
WORKDIR /src/fraud-service
RUN --mount=type=cache,target=/root/.gradle ./gradlew build -x test --no-daemon -q \
    -Dquarkus.native.enabled=true -Dquarkus.native.sources-only=true -Dquarkus.package.jar.enabled=false

# --- 2. El binario: Mandrel 25, la versión que pide Quarkus 3.40 (con la biblioteca de clases del JDK 25) ---------
FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25@sha256:bfea64bfc6e86d4bbb8f301ac227a4da08e5778c8d3b93b1e10ef3c35d16e32c AS native
COPY --from=sources --chown=quarkus:quarkus /src/fraud-service/build/native-sources /code
WORKDIR /code
# El análisis de native-image es lo que más memoria usa: 5 GB de heap (medido en el spike: pico de 6.3 GB de RSS).
# Sin comillas a propósito: cada línea de native-image.args tiene que llegar como un argumento aparte
# hadolint ignore=SC2046
RUN native-image $(cat native-image.args) -J-Xmx5g

# --- 3. Ejecución: la imagen mínima de Quarkus para binarios nativos (UBI 9: glibc 2.34, la que pide el binario) --
FROM quay.io/quarkus/ubi9-quarkus-micro-image:2.0@sha256:aa84cf7f06096050253e740b2b6e679044c268c4db7971483777f1c61a501190
WORKDIR /app
COPY --from=native --chown=10001:0 --chmod=0755 /code/fraud-service-0.0.1-SNAPSHOT-runner /app/application
USER 10001
EXPOSE 8085
# La imagen no trae curl ni grep: la readiness se pide con bash solo (/dev/tcp) y se mira la línea de estado
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8085 && printf 'GET /actuator/health/readiness HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && read -r status <&3 && [[ $status == *' 200 '* ]]"]
ENTRYPOINT ["/app/application"]
