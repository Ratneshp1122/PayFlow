FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B -ntp package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/payflow-1.0.0.jar app.jar
USER 10001
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65","-jar","app.jar"]
