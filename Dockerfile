FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src/main ./src/main
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/eventrush-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
