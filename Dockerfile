# Multi-stage Dockerfile for CivicPulse
# Stage 1: Build the Maven application
FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /workspace

# Copy POMs first to leverage Docker layer caching
COPY pom.xml ./
COPY yggdrasil/pom.xml yggdrasil/
COPY civicpulse/pom.xml civicpulse/

# Pre-download dependencies
RUN mvn dependency:go-offline -B -DexcludeGroupIds=com.civicpulse || true

# Copy full source trees
COPY yggdrasil yggdrasil
COPY civicpulse civicpulse

# Build shaded executable jar
RUN mvn clean package -DskipTests -B

# Stage 2: Minimal, secure JRE runtime
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Install curl for container health checks
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as unprivileged user
RUN groupadd -r civicpulse && useradd -r -g civicpulse civicpulse \
    && mkdir -p /app/data \
    && chown -R civicpulse:civicpulse /app

# Copy shaded JAR
COPY --from=builder /workspace/target/civicpulse.jar /app/civicpulse.jar

USER civicpulse

# Render defaults
ENV PORT=10000
ENV HOST=0.0.0.0
EXPOSE 10000

HEALTHCHECK --interval=20s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -f http://127.0.0.1:${PORT}/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java -XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Dserver.port=${PORT} -Dserver.host=${HOST} -jar /app/civicpulse.jar"]
