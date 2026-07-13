FROM maven:3.9.16-eclipse-temurin-26 AS build

WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN mvn -B package

FROM eclipse-temurin:26.0.1_8-jre

WORKDIR /app
RUN mkdir -p /app/data
COPY --from=build /workspace/target/*.jar /app/app.jar

VOLUME ["/app/data"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
