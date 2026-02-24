FROM eclipse-temurin:11-jdk AS build
WORKDIR /app
COPY . /app
RUN ./gradlew :manager:bootJar --no-daemon

FROM eclipse-temurin:11-jre
WORKDIR /app
COPY --from=build /app/manager/build/libs/manager-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]