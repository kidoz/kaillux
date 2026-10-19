# Admin UI build stage
FROM node:24-alpine@sha256:c921b97d4b74f51744057454b306b418cf693865e73b8100559189605f6955b8 AS admin-build
WORKDIR /app/admin

# Copy package files for caching
COPY src/main/resources/static/admin/package.json .
COPY src/main/resources/static/admin/package-lock.json* .

# Install dependencies
RUN npm ci

# Copy source and build
COPY src/main/resources/static/admin .
RUN npm run build

# Java build stage
FROM eclipse-temurin:25-jdk-noble@sha256:45266f9f6b93d2d3325404efceebf5b04ab4c27d54afc6e8e84bb7997365d8a0 AS build
WORKDIR /app

# Copy gradle files for caching
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Ensure gradlew is executable
RUN chmod +x gradlew

# Download dependencies (cache layer)
RUN ./gradlew dependencies --no-daemon

# Copy source
COPY src src

# Copy built admin UI to the path served by the Vite '/admin/' base
COPY --from=admin-build /app/admin/dist/ src/main/resources/static/admin/

# Build the application
RUN ./gradlew bootJar --no-daemon

# Run stage
FROM eclipse-temurin:25-jre-noble@sha256:d8dd4342b7dbb5a9c06d0499eecca86315346acc6a20026080642610344ceb2c
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app

# Copy the executable jar from the build stage
COPY --from=build /app/build/kaillux.jar .

# Copy configuration files to working directory (required for hot-reload support)
COPY --from=build /app/src/main/resources/access.cfg .

# Use IPv4 only (avoids dual-stack bind conflicts in container environments)
ENV CONTROLLERS_BIND_ADDRESSES=0.0.0.0

# Admin UI
EXPOSE 8080/tcp
# Kaillera Connect
EXPOSE 27888/udp
# Kaillera Data Ports (default range)
EXPOSE 27889-27924/udp

# Java 25: Enable Compact Object Headers for reduced memory footprint
ENTRYPOINT ["java", "-XX:+UseCompactObjectHeaders", "-jar", "kaillux.jar"]
