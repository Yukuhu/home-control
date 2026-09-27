#!/usr/bin/env bash
# Publishes the images the smoke tests pushed by digest as one multi-arch image under the tags
# of a release. Nothing is built or changed: the tags point at the images that were tested.
#
# usage: TAGS=<one image:tag per line> scripts/publish-images.sh DIGESTS_DIRECTORY ARCH[,ARCH...]
#
# DIGESTS_DIRECTORY holds one empty file per image, named by its sha256 digest, which is how
# the smoke test jobs hand them over. The architectures are only counted: a release that lacks
# one of them must fail rather than publish an image some hosts cannot run.
#
# In a workflow, the step's outputs `image` and `digest` name what was published.
set -euo pipefail

directory="${1:?usage: scripts/publish-images.sh DIGESTS_DIRECTORY ARCH[,ARCH...]}"
IFS=, read -ra architectures <<< "${2:?usage: scripts/publish-images.sh DIGESTS_DIRECTORY ARCH[,ARCH...]}"

mapfile -t tags < <(grep -v '^[[:space:]]*$' <<< "${TAGS:-}" || true)
(( ${#tags[@]} > 0 )) || { echo "TAGS names no tag to publish." >&2; exit 1; }

mapfile -t digests < <(find "$directory" -maxdepth 1 -type f -printf '%f\n' 2>/dev/null | sort)
for digest in "${digests[@]}"; do
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || { echo "Not a sha256 digest: $digest" >&2; exit 1; }
done
(( ${#digests[@]} == ${#architectures[@]} )) || {
  echo "Expected an image for each of ${architectures[*]}, found ${#digests[@]} in $directory." >&2
  exit 1
}

image="${tags[0]%:*}"
arguments=()
for tag in "${tags[@]}"; do
  [[ "${tag%:*}" == "$image" ]] || { echo "$tag is not a tag of $image." >&2; exit 1; }
  arguments+=(--tag "$tag")
done
for digest in "${digests[@]}"; do
  arguments+=("$image@sha256:$digest")
done

docker buildx imagetools create "${arguments[@]}"
docker buildx imagetools inspect "${tags[0]}"

published="$(docker buildx imagetools inspect "${tags[0]}" --format '{{json .Manifest}}' | jq -r .digest)"
[[ "$published" =~ ^sha256:[0-9a-f]{64}$ ]] || { echo "Could not read the digest of ${tags[0]}." >&2; exit 1; }
echo "Published $image@$published"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "image=$image"
    echo "digest=$published"
  } >> "$GITHUB_OUTPUT"
fi
