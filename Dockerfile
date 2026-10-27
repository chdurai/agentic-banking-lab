# Build the React workbench before packaging the Spring Boot application.
FROM node:24-alpine AS frontend
WORKDIR /workspace
COPY frontend ./frontend
RUN npm ci --prefix frontend && npm run build --prefix frontend

# Multi-stage build: build with Maven, run on a slim JRE
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
COPY --from=frontend /workspace/src/main/resources/static/react-workbench ./src/main/resources/static/react-workbench
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app

# Run as non-root
RUN groupadd -r app && useradd -r -g app app
USER app

COPY --from=build /workspace/target/agentic-banking-1.0-SNAPSHOT.jar app.jar

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
