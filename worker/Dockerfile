FROM eclipse-temurin:11-jdk AS build
WORKDIR /app
COPY . /app
RUN ./gradlew :worker:bootJar --no-daemon

FROM eclipse-temurin:11-jre
WORKDIR /app
COPY --from=build /app/worker/build/libs/worker-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]