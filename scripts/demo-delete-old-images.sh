#!/usr/bin/env bash
set -euo pipefail

: "${RECOVERY_BUNDLE:?RECOVERY_BUNDLE is required}"
[[ "${DEMO_IMAGE_CLEANUP_ACK:-}" == "DELETE_OLD_47461BE_IMAGES" ]] || {
  echo "Old image cleanup requires explicit acknowledgement" >&2
  exit 1
}
grep -Fqx 'recovery_verification=PASS' "$RECOVERY_BUNDLE/recovery-receipt.env" || {
  echo "Verified recovery receipt is required" >&2
  exit 1
}

old_images=(
  open-metadata-sync-demo-controller:47461be
  open-metadata-sync-demo-agent:47461be
  open-metadata-sync-demo-gateway:47461be
)
current=$(mktemp)
trap 'rm -f "$current"' EXIT
docker image inspect "${old_images[@]}" > "$current"
cmp "$RECOVERY_BUNDLE/old-images-inspect.json" "$current"
docker image rm "${old_images[@]}"
