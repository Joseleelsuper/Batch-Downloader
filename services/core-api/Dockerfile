FROM maven:3.9.16-eclipse-temurin-25 AS build

WORKDIR /workspace
COPY scripts/java /tmp/java-security
RUN sh /tmp/java-security/install-temurin.sh && sh /tmp/java-security/distrust-emudhra.sh
COPY pom.xml ./
COPY shared/java-contracts/pom.xml shared/java-contracts/pom.xml
COPY services/core-api/pom.xml services/core-api/pom.xml
COPY services/download-worker/pom.xml services/download-worker/pom.xml
COPY services/notification-service/pom.xml services/notification-service/pom.xml
COPY services/translation-service/pom.xml services/translation-service/pom.xml
COPY shared/java-contracts/src shared/java-contracts/src
COPY services/core-api/src services/core-api/src
RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*
RUN mvn -B -pl services/core-api -am -DskipTests package

FROM eclipse-temurin:25-jre

WORKDIR /app
COPY scripts/java /tmp/java-security
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl fontconfig fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*
RUN sh /tmp/java-security/install-temurin.sh \
    && sh /tmp/java-security/distrust-emudhra.sh \
    && rm -rf /tmp/java-security \
    && groupadd --gid 10001 batch \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin batch
COPY --from=build /workspace/services/core-api/target/*.jar /app/app.jar

EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
