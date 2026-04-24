# syntax=docker/dockerfile:1.7
#
# DeepGaze backend — Spring Boot 3.3 / Java 17 / embedded SQLite (WAL).
#
# Two stages:
#   1. build    — Temurin JDK 17, runs ./gradlew bootJar.
#   2. runtime  — Temurin JRE 17, runs the fat jar as a non-root user.
#
# The SQLite Time Machine writes three sidecars (.db, .db-wal, .db-shm)
# into /app/data. SQLite needs create/write/fsync on that directory
# itself (not just on the .db file), so /app/data is chown'd before
# we drop privileges. Declared as a VOLUME so the named compose volume
# inherits the correct ownership on first creation.

# ------------------------------------------------------------------
# Stage 1 — build the fat jar.
# ------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /src

COPY . .

# BuildKit cache mount: Gradle's dependency cache survives across builds
# without leaking into the final image. Dramatically speeds up re-builds
# when only src/ changes.
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x ./gradlew && \
    ./gradlew --no-daemon -q clean bootJar

# ------------------------------------------------------------------
# Stage 2 — minimal JRE runtime.
# ------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

# curl (~2 MB) is only pulled for the container healthcheck.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

ENV TZ=UTC \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"

# Non-root runtime user.
RUN groupadd --system --gid 1000 deepgaze \
 && useradd  --system --uid 1000 --gid deepgaze --home /app --shell /usr/sbin/nologin deepgaze \
 && mkdir -p /app/data \
 && chown -R deepgaze:deepgaze /app

WORKDIR /app
USER deepgaze:deepgaze

COPY --from=build --chown=deepgaze:deepgaze /src/build/libs/deepgaze-backend.jar /app/deepgaze-backend.jar

# SQLite history + WAL/SHM sidecars live here. Default db-path in
# application.yml resolves to ./data/deepgaze-history.db, which the
# JVM evaluates against WORKDIR → /app/data/deepgaze-history.db.
VOLUME ["/app/data"]

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java","-jar","/app/deepgaze-backend.jar"]
