FROM gradle:jdk25 AS build
WORKDIR /src
COPY . .
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:25-jre
# Bluetooth speakers (optional) need the mpv player, which adds about 430 MB.
# Default builds leave it out; --build-arg WITH_MPV=true (the -bluetooth image) installs it.
ARG WITH_MPV=false
RUN if [ "$WITH_MPV" = "true" ]; then \
      apt-get update \
      && apt-get install -y --no-install-recommends mpv \
      && rm -rf /var/lib/apt/lists/*; \
    fi
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
VOLUME /data
ENV SHIELD_DATA_DIR=/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--shield.data-dir=/data"]
