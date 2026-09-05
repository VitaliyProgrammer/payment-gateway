# Build stage: compiles the whole reactor (gateway needs the parent pom) but ships
# only the gateway module's jar into the runtime image.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY pom.xml .
COPY gateway/pom.xml gateway/pom.xml
COPY mock-acquirer/pom.xml mock-acquirer/pom.xml
COPY .mvn .mvn
COPY mvnw .

RUN chmod +x mvnw && ./mvnw -q -pl gateway -am dependency:go-offline

COPY gateway/src gateway/src
RUN ./mvnw -q -pl gateway -am package -DskipTests

# Runtime stage: JRE only, non-root user, no build tooling in the final image.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S payflow && adduser -S payflow -G payflow
USER payflow

WORKDIR /app
COPY --from=build /workspace/gateway/target/gateway-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
