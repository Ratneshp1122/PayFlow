FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B -ntp package -DskipTests
RUN java -Djarmode=tools -jar target/payflow-1.0.0.jar extract --destination extracted
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/extracted/ ./
# Training only: avoid contacting dependencies. Runtime retains Flyway, workers and listeners.
RUN java -XX:MaxRAMPercentage=65 -XX:ArchiveClassesAtExit=application.jsa -Dspring.context.exit=onRefresh -jar payflow-1.0.0.jar --spring.flyway.enabled=false --spring.datasource.hikari.minimum-idle=0 --spring.datasource.hikari.initialization-fail-timeout=-1 --spring.kafka.listener.auto-startup=false --payflow.workers=false
USER 10001
# Fail explicitly if the archive is incompatible; do not silently report a non-CDS trial.
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65","-Xshare:on","-XX:SharedArchiveFile=application.jsa","-jar","payflow-1.0.0.jar"]
