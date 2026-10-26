# syntax=docker/dockerfile:1
# Imagen de api-gateway (BANK-92, spike BANK-91). Se construye desde la raíz del repo:
#   docker build -f api-gateway/Dockerfile -t bank-api-gateway:local .
# Dos etapas: la primera compila con el JDK y Gradle; la segunda solo lleva el JRE y la aplicación, sin código
# fuente ni Gradle. Las bases van fijadas por digest (como las actions por SHA): la misma receta da la misma imagen

# --- Construcción ---------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-noble@sha256:107215954359f1ab0be058a247d7c64ed6e69c71c1682d471d38ef751ec8e964 AS build
WORKDIR /src
# Lo que compila el servicio: su carpeta y su includeBuild (platform-bom) más gradle/quality.gradle
COPY gradle gradle
COPY platform-bom platform-bom
COPY api-gateway api-gateway
WORKDIR /src/api-gateway
# La caché de Gradle (distribución y dependencias) queda en BuildKit entre construcciones: con un cambio de código
# no se vuelve a bajar nada. El jar se separa en capas (dependencias / loader / snapshots / aplicación)
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon -q \
 && cp build/libs/api-gateway-*.jar /app.jar \
 && cd / && java -Djarmode=tools -jar /app.jar extract --layers --destination /extracted

# --- Ejecución ------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble@sha256:35501d3f93173b420ac9001658eb68157e813316b865087e345c187fca422c4c
# Usuario sin privilegios con un UID fijo (el mismo en todas las imágenes; en AKS, runAsNonRoot)
RUN groupadd --system --gid 10001 bank && useradd --system --uid 10001 --gid bank --no-create-home bank
WORKDIR /app
# De lo que menos cambia a lo que más: un cambio de código solo reemplaza la última capa (cientos de kB), no las
# dependencias (~130 MB)
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001
EXPOSE 8080
# Docker y compose saben si el servicio está listo para recibir tráfico: la readiness (en AKS, la readinessProbe)
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness > /dev/null || exit 1
# Sin -Xmx fijo: el heap es el 60 % del límite de memoria del contenedor. El resto es para lo que la JVM usa fuera
# del heap (metaspace, hilos, buffers de Netty), unos 150-200 MB en estos servicios (BANK-94). Va en
# JAVA_TOOL_OPTIONS y no en el ENTRYPOINT para poder cambiarlo al correr, sin reconstruir la imagen
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60"
ENTRYPOINT ["java", "-jar", "app.jar"]
