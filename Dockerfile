# ---- Etapa 1: compilación (Maven + JDK 21) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- Etapa 2: imagen de ejecución (solo JRE) ----
FROM eclipse-temurin:21-jre-alpine
# Parches de seguridad de Alpine más nuevos que los de la imagen base (Trivy: p. ej. brotli-libs y
# libexpat, 2026-10-26). Sin esto el escaneo del CI falla hasta que publiquen otra eclipse-temurin.
# APK_REFRESH cambia en cada ejecución del CI (github.run_id): invalida la caché de capas (type=gha)
# desde aquí, para que el upgrade se repita y traiga los parches publicados después (p. ej. libexpat
# 2.10.0, 2026-11). Sin ella, buildx reutilizaba la capa vieja con los paquetes vulnerables.
ARG APK_REFRESH=local
RUN echo "apk refresh: ${APK_REFRESH}" && apk upgrade --no-cache
RUN addgroup -S tripbus && adduser -S tripbus -G tripbus
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER tripbus
ARG GIT_COMMIT=local
ENV SERVER_PORT=18005 APP_COMMIT=${GIT_COMMIT}
EXPOSE 18005
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
HEALTHCHECK --interval=10s --timeout=3s --start-period=120s --retries=6 \
  CMD wget -qO- http://localhost:${SERVER_PORT}/actuator/health/readiness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
