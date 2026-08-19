# Build context is the repo root (see docker-compose.yml: context: .) — see order-service/Dockerfile
# for why the full services/ tree must be copied even though only common + this service get built.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY services services
RUN mvn -q -pl services/common,services/scenario-service -am package -DskipTests
RUN rm -f services/scenario-service/target/*.jar.original

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/services/scenario-service/target/*.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
