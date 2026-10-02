#!/usr/bin/env bash
# Shared by the local build helpers; HC_CONTAINER_RUNTIME can explicitly select a runtime.
if [[ -n "${HC_CONTAINER_RUNTIME:-}" ]]; then
  HC_CONTAINER_ENGINE="$HC_CONTAINER_RUNTIME"
elif command -v docker >/dev/null 2>&1; then
  HC_CONTAINER_ENGINE=docker
else
  HC_CONTAINER_ENGINE=podman
fi
if ! command -v "$HC_CONTAINER_ENGINE" >/dev/null 2>&1; then
  echo "Install Docker or Podman, or set HC_CONTAINER_RUNTIME to an available runtime." >&2
  exit 1
fi
HC_CONTAINER_RUN_OPTIONS=()
if [[ "$(basename "$HC_CONTAINER_ENGINE")" == podman ]]; then
  # Match bind-mounted file ownership without relabelling the checkout or the user's cache.
  HC_CONTAINER_RUN_OPTIONS=(--userns=keep-id --security-opt=label=disable)
fi
