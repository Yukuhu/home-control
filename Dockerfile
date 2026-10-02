# The one image, built two ways:
#
# - From source, as `docker compose up --build` does: the build stage compiles the jar.
# - From a jar CI built once, natively: `--build-context jar=<a directory holding only that jar>` replaces the jar
#   stage, so the build stage never runs. Java bytecode is architecture-independent, so only the JRE layer differs
#   between linux/amd64 and linux/arm64, and the multi-arch build takes seconds instead of a Gradle build under QEMU.
FROM gradle:jdk25@sha256:30f0c2e94f2b91cffaa192cebc86f1ba8efc574b9a8e93b33164e5f2ed839c08 AS build
# Gradle and the dependencies it downloads stay in a cache mount, so a rebuild from a changed checkout reuses them.
ENV GRADLE_USER_HOME=/gradle-home
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/gradle-home ./gradlew --no-daemon bootJar

# The jar the runtime takes; see the top of this file.
FROM scratch AS jar
COPY --from=build /src/build/libs/*.jar /

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
COPY --from=jar /*.jar /app/app.jar
# The app runs as a user without root rights: uid 1000, the image's own `ubuntu`. /data is handed
# to that user here, so that a volume Docker creates for it belongs to that user too. A directory
# of the host mounted there keeps the owner it has on the host.
# The -bluetooth image is built with RUN_AS=0:0 and stays root, because the host's D-Bus lets
# only root talk to BlueZ (docs/user/bluetooth-speakers.md).
ARG RUN_AS=1000:1000
RUN mkdir -p /data && chown "$RUN_AS" /data
VOLUME /data
ENV HOME_CONTROL_DATA_DIR=/data
EXPOSE 8080
# Healthy when the server answers GET /health. The image has bash but neither curl nor wget, so bash asks over
# /dev/tcp. The Host header is the one a browser on this machine would send.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT:-8080} && printf 'GET /health HTTP/1.0\\r\\nHost: 127.0.0.1\\r\\n\\r\\n' >&3 && head -n1 <&3 | grep -q ' 200 '"]
USER ${RUN_AS}
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--home-control.data-dir=/data"]
