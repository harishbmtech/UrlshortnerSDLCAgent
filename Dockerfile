# Multi-stage build of the Spring Boot app. Ollama runs as a separate container (see docker-compose.yml).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/agentic-sdlc-url-shortener-1.0.0.jar app.jar
# The brownfield agents reason about the shortener's own source, so ship it next to the jar.
COPY src/main/java/com/example/shortener src/main/java/com/example/shortener
COPY src/main/resources/db/migration src/main/resources/db/migration
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
