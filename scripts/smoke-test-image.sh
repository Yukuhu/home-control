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
work="$(mktemp -d)"

cleanup() {
  docker rm --force --volumes "$name" >/dev/null 2>&1 || true
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

if $bluetooth; then
  docker run --rm --entrypoint mpv "$image" --no-config --version > "$work/mpv" 2>&1 \
    || { cat "$work/mpv" >&2; fail "mpv does not run in the image"; }
  echo "mpv: $(head -n 1 "$work/mpv")"
fi

run_args=(--detach --name "$name" --publish "127.0.0.1:$port:8080")
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

curl --silent --show-error --fail --output "$work/setup.html" "http://127.0.0.1:$port/setup" \
  || fail "the setup page did not render"
echo "Setup page rendered."

if $bluetooth; then
  # One <li data-check="..." class="ok|problem"> per host check. Without BlueZ, an adapter and an
  # audio server only mpv can pass; the rest just have to be reported rather than break the page.
  checks="$(grep -Eo '<li [^>]*data-check="[^"]*"[^>]*>' "$work/setup.html" \
    | sed -E 's/.*data-check="([^"]*)".*/\1 &/; s/^([^ ]*) .*class="([^"]*)".*/Host check \1: \2/')" || true
  echo "$checks"
  grep -qx 'Host check mpv: ok' <<< "$checks" || fail "the setup page does not report mpv as working"
fi

echo "Smoke test passed: $image"
