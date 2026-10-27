# Development plan backend: the Spring Boot API (/api), a thin layer over the case engine.
# The browser app is a separate image (../frontend), which forwards /api to this container.

# 1. Build the jar
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /api
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# 2. Run it
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /api/target/devplan-app-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
