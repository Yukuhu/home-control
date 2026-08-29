FROM gradle:jdk25 AS build
WORKDIR /src
COPY . .
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
VOLUME /data
ENV SHIELD_DATA_DIR=/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--shield.data-dir=/data"]
