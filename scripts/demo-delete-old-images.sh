#!/usr/bin/env bash
set -euo pipefail

: "${RECOVERY_BUNDLE:?RECOVERY_BUNDLE is required}"
: "${RECOVERY_KEY_FILE:?RECOVERY_KEY_FILE is required}"
: "${LIVE_VALIDATION_RECEIPT_FILE:?LIVE_VALIDATION_RECEIPT_FILE is required}"
: "${CANDIDATE_REVISION:?CANDIDATE_REVISION is required}"
[[ "${DEMO_IMAGE_CLEANUP_ACK:-}" == "DELETE_OLD_47461BE_IMAGES" ]] || {
  echo "Old image cleanup requires explicit acknowledgement" >&2
  exit 1
}
openssl pkeyutl -verify -rawin -inkey "$RECOVERY_KEY_FILE" \
  -in "$RECOVERY_BUNDLE/SHA256SUMS" -sigfile "$RECOVERY_BUNDLE/SHA256SUMS.sig" >/dev/null
openssl pkeyutl -verify -rawin -inkey "$RECOVERY_KEY_FILE" \
  -in "$RECOVERY_BUNDLE/recovery-receipt.env" \
  -sigfile "$RECOVERY_BUNDLE/recovery-receipt.env.sig" >/dev/null
grep -Fqx 'recovery_verification=PASS' "$RECOVERY_BUNDLE/recovery-receipt.env" || {
  echo "Verified recovery receipt is required" >&2
  exit 1
}
grep -Fqx "candidate_revision=$CANDIDATE_REVISION" "$RECOVERY_BUNDLE/recovery-receipt.env" || {
  echo "Recovery receipt candidate mismatch" >&2
  exit 1
}
(cd "$RECOVERY_BUNDLE" && shasum -a 256 -c SHA256SUMS)
grep -Fqx 'live_demo_validation=PASS' "$LIVE_VALIDATION_RECEIPT_FILE" || {
  echo "Live validation receipt is required" >&2
  exit 1
}
grep -Fqx 'validation_scope=deployed' "$LIVE_VALIDATION_RECEIPT_FILE" || {
  echo "Only deployed validation may authorize old image cleanup" >&2
  exit 1
}
grep -Fqx 'visitor_path=PASS' "$LIVE_VALIDATION_RECEIPT_FILE"
grep -Fqx 'otp_access=PASS' "$LIVE_VALIDATION_RECEIPT_FILE"
grep -Fqx "candidate_revision=$CANDIDATE_REVISION" "$LIVE_VALIDATION_RECEIPT_FILE" || {
  echo "Live validation candidate mismatch" >&2
  exit 1
}
cmp "$RECOVERY_BUNDLE/live-validation.env" "$LIVE_VALIDATION_RECEIPT_FILE" || {
  echo "Live validation receipt differs from the recovery snapshot" >&2
  exit 1
}

old_images=(
  open-metadata-sync-demo-controller:47461be
  open-metadata-sync-demo-agent:47461be
  open-metadata-sync-demo-gateway:47461be
)
docker image inspect "${old_images[@]}" >/dev/null
docker image rm "${old_images[@]}"
