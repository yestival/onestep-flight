FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre-jammy AS runtime

RUN groupadd --system --gid 10001 onestep \
    && useradd --system --uid 10001 --gid onestep --create-home --home-dir /app onestep

WORKDIR /app
COPY --from=build --chown=onestep:onestep /workspace/target/onestep-flight-*.jar /app/app.jar

USER onestep
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/urandom", "-jar", "/app/app.jar"]
