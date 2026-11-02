# Izgradnja: Maven unutar Docker-a, pa računar ne mora imati Javu ni Maven (Project Info §14.3).
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# Pokretanje: samo JRE i jedan jar.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/dlsm.jar /app/dlsm.jar
ENV NODE_ID=standalone \
    DATA_DIR=/var/lib/dlsm \
    PUBLIC_PORT=8080 \
    ADMIN_PORT=8082
EXPOSE 8080 8082
VOLUME /var/lib/dlsm
ENTRYPOINT ["java", "-jar", "/app/dlsm.jar"]
