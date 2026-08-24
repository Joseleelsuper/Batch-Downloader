FROM maven:3.9.16-eclipse-temurin-26 AS build

WORKDIR /workspace
COPY pom.xml ./
COPY shared/java-contracts/pom.xml shared/java-contracts/pom.xml
COPY services/core-api/pom.xml services/core-api/pom.xml
COPY services/download-worker/pom.xml services/download-worker/pom.xml
COPY services/notification-service/pom.xml services/notification-service/pom.xml
COPY services/translation-service/pom.xml services/translation-service/pom.xml
COPY shared/java-contracts/src shared/java-contracts/src
COPY services/notification-service/src services/notification-service/src
RUN mvn -B -pl services/notification-service -am -DskipTests package

FROM eclipse-temurin:26.0.1_8-jre

WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/data
COPY --from=build /workspace/services/notification-service/target/*.jar /app/app.jar

EXPOSE 8080
VOLUME ["/app/data"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
