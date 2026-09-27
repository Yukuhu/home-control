FROM gradle:jdk25@sha256:30f0c2e94f2b91cffaa192cebc86f1ba8efc574b9a8e93b33164e5f2ed839c08 AS build
WORKDIR /src
COPY . .
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:25-jre@sha256:8da0490fa9a3c26867012019565948eef0ee69438f5c75ac28146967bae984b5
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
