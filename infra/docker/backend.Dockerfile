# Backend API Dockerfile
#
# Multi-stage build for the Kixi Backend API (Spring Boot WebFlux)
# Optimized for production deployments with minimal image size
#
# Build from project root:
#   docker build -f infra/docker/backend.Dockerfile -t kixi-backend-api .
#
# Or using docker-compose (recommended):
#   docker-compose up --build backend-api

# =============================================================================
# Stage 1: Builder
# =============================================================================
FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /app

# Copy Maven wrapper and configuration from services/backend-api
COPY services/backend-api/mvnw .
COPY services/backend-api/.mvn .mvn
COPY services/backend-api/pom.xml .

# Make Maven wrapper executable
RUN chmod +x mvnw

# Download dependencies (cached layer)
RUN ./mvnw dependency:go-offline -B

# Copy source code from services/backend-api
COPY services/backend-api/src ./src

# Build the application (skip tests for faster builds)
RUN ./mvnw clean package -DskipTests

# =============================================================================
# Stage 2: Runtime
# =============================================================================
FROM eclipse-temurin:17-jre-jammy AS runtime

# Labels
LABEL maintainer="Kixi Team <team@kixi.ao>" \
    org.opencontainers.image.title="Kixi Backend API" \
    org.opencontainers.image.description="Spring Boot WebFlux Backend API for the Kixi platform" \
    org.opencontainers.image.version="0.0.1-SNAPSHOT" \
    org.opencontainers.image.vendor="Creative Mode" \
    org.opencontainers.image.source="https://github.com/creative-mode/kixi"

# Set environment variables
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0" \
    SPRING_PROFILES_ACTIVE=docker \
    SERVER_PORT=8080 \
    TZ=Africa/Luanda

# Install useful tools
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    tzdata \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user for security
RUN groupadd --gid 1000 spring && \
    useradd --uid 1000 --gid spring --shell /bin/bash --create-home spring

# Set working directory
WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=build /app/target/demo-0.0.1-SNAPSHOT.jar app.jar

# Change ownership to non-root user
RUN chown -R spring:spring /app

# Switch to non-root user
USER spring

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
