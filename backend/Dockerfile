# Build the Spring Boot backend
FROM maven:3.9.6-eclipse-temurin-17 AS backend-build
WORKDIR /app
COPY backend/pom.xml .
# Download dependencies to cache them
RUN mvn dependency:go-offline -B
COPY backend/src ./src
RUN mvn package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=backend-build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200", "-XX:+UseStringDeduplication", "-Xms512m", "-Xmx768m", "-jar", "app.jar"]

