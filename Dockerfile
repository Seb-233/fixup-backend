FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
RUN ./mvnw --batch-mode --no-transfer-progress clean verify

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system fixup && useradd --system --gid fixup fixup
COPY --from=build /workspace/target/fixup-backend-0.0.1-SNAPSHOT.jar app.jar
USER fixup:fixup
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
