# Build context is the repo root (see docker-compose.yml: context: .) because Maven's reactor
# requires every module listed in the root pom.xml's <modules> to exist on disk even when -pl
# restricts which ones actually get built (verified empirically — `mvn -pl A,B -am` still fails
# fast with "Child module ... does not exist" if a sibling module directory is absent). So the
# build stage copies the whole services/ tree, then -pl/-am compiles only common + this service.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY services services
RUN mvn -q -pl services/common,services/order-service -am package -DskipTests
RUN rm -f services/order-service/target/*.jar.original

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/services/order-service/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
