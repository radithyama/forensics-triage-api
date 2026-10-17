# syntax=docker/dockerfile:1.7

# ---- Build: resolve dependencies in their own layer so code changes don't re-download them ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package \
 && java -Djarmode=tools -jar target/api-gateway.jar extract --layers --launcher --destination /extracted

# ---- Runtime: JRE only, non-root, layered for cache-friendly pushes ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app \
 && mkdir -p /data/uploads /data/tmp && chown -R app:app /data
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001
# Multipart parts are spooled to java.io.tmpdir; keeping it on the same volume as uploads makes
# the final transferTo a cheap rename instead of a second multi-GB copy.
ENV UPLOAD_DIR=/data/uploads \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/data/tmp"
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
