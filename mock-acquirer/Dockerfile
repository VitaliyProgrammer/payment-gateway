FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY pom.xml .
COPY gateway/pom.xml gateway/pom.xml
COPY mock-acquirer/pom.xml mock-acquirer/pom.xml
COPY .mvn .mvn
COPY mvnw .

RUN chmod +x mvnw && ./mvnw -q -pl mock-acquirer -am dependency:go-offline

COPY mock-acquirer/src mock-acquirer/src
RUN ./mvnw -q -pl mock-acquirer -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S payflow && adduser -S payflow -G payflow
USER payflow

WORKDIR /app
COPY --from=build /workspace/mock-acquirer/target/mock-acquirer-*.jar app.jar

EXPOSE 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
