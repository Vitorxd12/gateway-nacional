# syntax=docker/dockerfile:1.7

# ============================================================================
# Stage 1 — Builder: compiles the fat jar inside a Maven + JDK 25 image.
# ============================================================================
FROM maven:3-eclipse-temurin-26 AS builder

WORKDIR /build

# Copy the POM first so Maven dependency resolution layer is cached
# independently from source changes.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp clean package -DskipTests

# ============================================================================
# Stage 2 — Runner: minimal JRE 25 alpine image, non-root user.
# ============================================================================
FROM eclipse-temurin:25-jre-alpine AS runner

# Non-root execution for defense in depth.
RUN addgroup -S app && adduser -S app -G app

WORKDIR /app

COPY --from=builder /build/target/*.jar app.jar
# Create data and work directories with correct permissions before switching user
RUN mkdir -p /app/data /home/app/.gateway-nacional && \
    chown -R app:app /app /home/app/.gateway-nacional

USER app
EXPOSE 8080

# MaxRAMPercentage=75 plays nicely with container memory limits.
# UseContainerSupport is on by default since JDK 10 but kept explicit for clarity.
ENTRYPOINT ["java", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75", \
            "-Djava.security.egd=file:/dev/./urandom", \
            "-jar", "/app/app.jar"]
