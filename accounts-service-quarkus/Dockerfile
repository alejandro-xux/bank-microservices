# syntax=docker/dockerfile:1
# Imagen de accounts-service-quarkus (BANK-99), con las mismas reglas que las de Spring (BANK-92): dos etapas, bases
# fijadas por digest, usuario 10001 y HEALTHCHECK. Se construye desde la raíz del repo:
#   docker build -f accounts-service-quarkus/Dockerfile -t bank-accounts-service-quarkus:local .

# --- Construcción ---------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-noble@sha256:107215954359f1ab0be058a247d7c64ed6e69c71c1682d471d38ef751ec8e964 AS build
WORKDIR /src
# Lo que compila el servicio: su carpeta, contracts y platform-bom (includeBuild) y gradle/sonar-libraries.gradle
COPY gradle gradle
COPY platform-bom platform-bom
COPY contracts contracts
COPY accounts-service-quarkus accounts-service-quarkus
WORKDIR /src/accounts-service-quarkus
# quarkusBuild deja la aplicación en build/quarkus-app (formato fast-jar), ya separada en capas
RUN --mount=type=cache,target=/root/.gradle ./gradlew quarkusBuild --no-daemon -q

# --- Ejecución ------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble@sha256:35501d3f93173b420ac9001658eb68157e813316b865087e345c187fca422c4c
RUN groupadd --system --gid 10001 bank && useradd --system --uid 10001 --gid bank --no-create-home bank
WORKDIR /app
# De lo que menos cambia a lo que más: las dependencias, el arranque de Quarkus y al final la aplicación
COPY --from=build /src/accounts-service-quarkus/build/quarkus-app/lib/ ./lib/
COPY --from=build /src/accounts-service-quarkus/build/quarkus-app/quarkus-run.jar ./
COPY --from=build /src/accounts-service-quarkus/build/quarkus-app/quarkus/ ./quarkus/
COPY --from=build /src/accounts-service-quarkus/build/quarkus-app/app/ ./app/
USER 10001
EXPOSE 8084
# Listo para recibir tráfico: la readiness, en la misma ruta que los servicios Spring
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
  CMD ["curl", "-fsS", "-o", "/dev/null", "http://localhost:8084/actuator/health/readiness"]
# El heap, como en los servicios Spring: 60 % del límite del contenedor (BANK-94)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60"
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
