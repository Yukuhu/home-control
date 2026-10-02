#!/usr/bin/env bash
# Starts a built image and checks that it runs natively on this Docker host and serves its pages.
# CI runs it on a native arm64 runner (the image-arm64 job); it works the same on any Linux Docker
# host, a Raspberry Pi included, so a local build can be checked before it is trusted:
#
#   docker build -t home-control:smoke . && scripts/smoke-test-image.sh home-control:smoke
#   docker build --build-arg WITH_MPV=true -t home-control:smoke-bluetooth . \
#     && scripts/smoke-test-image.sh --bluetooth home-control:smoke-bluetooth
#
# --bluetooth is for the -bluetooth image: it runs the bundled mpv, then turns the Bluetooth
# module on so the setup page runs its host checks (D-Bus from the JVM, mpv launched by the app).
# No adapter or BlueZ is needed; the page reports them missing and must still render.
#
# The image must run as a user without root rights who can write to its data volume, and must
# say how to hand over a data directory that an older version, which ran as root, left behind.
# The -bluetooth image must run as root, which the host's D-Bus requires of it.
#
# SMOKE_PORT (default 18080) is the loopback port the container is published on, clear of an
# instance already running on 8080; SMOKE_TIMEOUT (default 120) is how many seconds it gets to start.
set -euo pipefail

bluetooth=false
if [[ "${1:-}" == "--bluetooth" ]]; then
  bluetooth=true
  shift
fi
image="${1:?usage: scripts/smoke-test-image.sh [--bluetooth] IMAGE}"
port="${SMOKE_PORT:-18080}"
timeout="${SMOKE_TIMEOUT:-120}"
name="home-control-smoke-$$"
volume="home-control-smoke-data-$$"
work="$(mktemp -d)"

cleanup() {
  docker rm --force --volumes "$name" "$name-upgrade" >/dev/null 2>&1 || true
  docker volume rm --force "$volume" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

fail() {
  echo "Smoke test failed: $*" >&2
  if docker container inspect "$name" >/dev/null 2>&1; then
    echo "--- container log (last 200 lines) ---" >&2
    docker logs --tail 200 "$name" >&2 2>&1 || true
  fi
  exit 1
}

# A foreign-architecture image would only run under QEMU, which is exactly what this is here to avoid.
host_arch="$(docker version --format '{{.Server.Arch}}')"
image_arch="$(docker image inspect --format '{{.Architecture}}' "$image")"
# (The containerd image store reports no architecture at all for an image with no variant for this host.)
[[ "$image_arch" == "$host_arch" ]] \
  || fail "$image has no $host_arch variant${image_arch:+ (it is $image_arch)}, so this $host_arch host could only emulate it"
echo "Image architecture: $image_arch (native on this host)"

docker run --rm --entrypoint java "$image" -XshowSettings:properties -version > "$work/java" 2>&1 \
  || { cat "$work/java" >&2; fail "java does not run in the image"; }
grep -E '^ *(os\.arch|java\.runtime\.version) =' "$work/java" | sed 's/^ */JVM: /'

user="$(docker run --rm --entrypoint id "$image" -u)"
if $bluetooth; then
  [[ "$user" == 0 ]] || fail "the -bluetooth image runs as user $user, but only root may talk to BlueZ"
else
  [[ "$user" != 0 ]] || fail "the image runs as root"
fi
echo "Runs as user: $user"

if $bluetooth; then
  docker run --rm --entrypoint mpv "$image" --no-config --version > "$work/mpv" 2>&1 \
    || { cat "$work/mpv" >&2; fail "mpv does not run in the image"; }
  echo "mpv: $(head -n 1 "$work/mpv")"
fi

# The image's HEALTHCHECK runs every 2 s here instead of every 30 s, so a healthy app is reported quickly.
run_args=(--detach --name "$name" --publish "127.0.0.1:$port:8080" --health-interval 2s)
if $bluetooth; then
  run_args+=(--env HOME_CONTROL_BLUETOOTH_ENABLED=true)
  # The host's system bus when it has one (a GitHub runner does), so the JVM's D-Bus client has a real bus to try.
  if [[ -S /run/dbus/system_bus_socket ]]; then
    run_args+=(--volume /run/dbus:/run/dbus:ro)
  fi
fi
docker run "${run_args[@]}" "$image" > /dev/null

# A fresh install has no devices, so / redirects to the setup page.
deadline=$((SECONDS + timeout))
until curl --silent --fail --location --output /dev/null "http://127.0.0.1:$port/"; do
  [[ "$(docker container inspect --format '{{.State.Running}}' "$name")" == true ]] \
    || fail "the container exited before it answered on port $port"
  ((SECONDS < deadline)) || fail "no answer on port $port within ${timeout}s"
  sleep 2
done
docker logs "$name" > "$work/log" 2>&1
grep -m 1 'Started HomeControlApplication' "$work/log" || true

# Compose, CasaOS and `docker ps` read the container's health from the image's HEALTHCHECK.
until [[ "$(docker container inspect --format '{{.State.Health.Status}}' "$name")" == healthy ]]; do
  if ((SECONDS >= deadline)); then
    docker container inspect --format '{{json .State.Health}}' "$name" >&2 || true
    fail "the container answered on port $port but did not report itself healthy within ${timeout}s"
  fi
  sleep 2
done
echo "Health: healthy"

curl --silent --show-error --fail --output "$work/setup.html" "http://127.0.0.1:$port/setup" \
  || fail "the setup page did not render"
echo "Setup page rendered."

docker exec "$name" sh -c 'touch /data/.smoke-test && rm /data/.smoke-test' \
  || fail "user $user cannot write to the data volume"
echo "Data volume is writable."

if ! $bluetooth; then
  # What an upgrade finds: a data directory whose files belong to root and are closed to others.
  docker run --rm --user 0:0 --volume "$volume:/data" --entrypoint sh "$image" \
    -c 'echo "[]" > /data/devices.json && chown 0:0 /data/devices.json && chmod 600 /data/devices.json'
  docker run --name "$name-upgrade" --volume "$volume:/data" "$image" > "$work/upgrade" 2>&1 \
    && fail "the app started on a data directory that belongs to root"
  grep -A 12 'APPLICATION FAILED TO START' "$work/upgrade" | grep -q 'chown -R 1000:1000' \
    || { tail -n 50 "$work/upgrade" >&2; fail "the app does not say how to hand over a data directory that belongs to root"; }
  echo "A data directory that belongs to root is refused, with the way to hand it over."
fi

if $bluetooth; then
  # One <li data-check="..." class="ok|problem"> per host check. Without BlueZ, an adapter and an
  # audio server only mpv can pass; the rest just have to be reported rather than break the page.
  checks="$(grep -Eo '<li [^>]*data-check="[^"]*"[^>]*>' "$work/setup.html" \
    | sed -E 's/.*data-check="([^"]*)".*/\1 &/; s/^([^ ]*) .*class="([^"]*)".*/Host check \1: \2/')" || true
  echo "$checks"
  grep -qx 'Host check mpv: ok' <<< "$checks" || fail "the setup page does not report mpv as working"
fi

echo "Smoke test passed: $image"
