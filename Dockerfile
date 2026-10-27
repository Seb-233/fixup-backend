FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
# CI validates tests with Docker/Testcontainers before CD; image build only packages the JAR.
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests clean package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system fixup && useradd --system --gid fixup fixup
COPY --from=build /workspace/target/fixup-backend-*.jar app.jar
USER fixup:fixup
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
