# Sanchiti API (Spring Boot). Needs Postgres: set DB_URL / DB_USER / DB_PASSWORD (see .env.example).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
# Tests need a Postgres instance; they run on the host with ./mvnw test.
RUN mvn -q -B package -DskipTests && cp target/sanchiti-*.jar /src/app.jar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/app.jar app.jar
USER app
ENV HOST=0.0.0.0 \
    PORT=8787 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k"
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD wget -qO- "http://127.0.0.1:${PORT}/health" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
