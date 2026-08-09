#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"

test_dir=$(mktemp -d)
cleanup() { rm -rf "${test_dir:?}"; }
trap cleanup EXIT
mkdir -p "$test_dir/recovery-root" "$test_dir/bundle"
openssl genpkey -algorithm ED25519 -out "$test_dir/recovery.key" >/dev/null 2>&1
openssl pkey -in "$test_dir/recovery.key" -pubout -out "$test_dir/recovery.pub" >/dev/null 2>&1
openssl genpkey -algorithm ED25519 -out "$test_dir/recovery-root/key-inside" >/dev/null 2>&1
openssl genpkey -algorithm ED25519 -out "$test_dir/wrong.key" >/dev/null 2>&1
openssl pkey -in "$test_dir/wrong.key" -pubout -out "$test_dir/wrong.pub" >/dev/null 2>&1
chmod 600 "$test_dir/recovery.key" "$test_dir/recovery-root/key-inside"
candidate=$(git rev-parse HEAD)

if RECOVERY_ROOT="$test_dir/recovery-root" RECOVERY_KEY_FILE="$test_dir/recovery-root/key-inside" \
    RECOVERY_PUBLIC_KEY_FILE="$test_dir/recovery.pub" \
    CANDIDATE_REVISION="$candidate" LIVE_VALIDATION_RECEIPT_FILE="$test_dir/missing.env" \
    RECOVERY_EXPORT_ACK=STOP_LIVE_RUNTIME_AND_EXPORT scripts/demo-export-recovery.sh >/dev/null 2>&1; then
  echo "Recovery export accepted a key inside the recovery root" >&2
  exit 1
fi
printf 'weak-key\n' > "$test_dir/weak.key"
chmod 600 "$test_dir/weak.key"
if RECOVERY_ROOT="$test_dir/recovery-root" RECOVERY_KEY_FILE="$test_dir/weak.key" \
    RECOVERY_PUBLIC_KEY_FILE="$test_dir/recovery.pub" \
    CANDIDATE_REVISION="$candidate" LIVE_VALIDATION_RECEIPT_FILE="$test_dir/missing.env" \
    RECOVERY_EXPORT_ACK=STOP_LIVE_RUNTIME_AND_EXPORT scripts/demo-export-recovery.sh >/dev/null 2>&1; then
  echo "Recovery export accepted a weak non-private key" >&2
  exit 1
fi

printf 'original\n' > "$test_dir/bundle/payload.enc"
(
  cd "$test_dir/bundle"
  shasum -a 256 payload.enc > SHA256SUMS
)
manifest_sha=$(shasum -a 256 "$test_dir/bundle/SHA256SUMS" | awk '{print $1}')
printf 'recovery_verification=PENDING\ncandidate_revision=%s\nbundle_manifest_sha256=%s\n' \
  "$candidate" "$manifest_sha" > "$test_dir/bundle/recovery-receipt.env"
openssl pkeyutl -sign -rawin -inkey "$test_dir/recovery.key" \
  -in "$test_dir/bundle/SHA256SUMS" -out "$test_dir/bundle/SHA256SUMS.sig"
openssl pkeyutl -sign -rawin -inkey "$test_dir/recovery.key" \
  -in "$test_dir/bundle/recovery-receipt.env" -out "$test_dir/bundle/recovery-receipt.env.sig"
printf 'tampered\n' > "$test_dir/bundle/payload.enc"
if RECOVERY_BUNDLE="$test_dir/bundle" RECOVERY_KEY_FILE="$test_dir/recovery.key" \
    RECOVERY_PUBLIC_KEY_FILE="$test_dir/wrong.pub" \
    scripts/demo-verify-recovery.sh >/dev/null 2>&1; then
  echo "Recovery verification accepted the wrong public key" >&2
  exit 1
fi
if RECOVERY_BUNDLE="$test_dir/bundle" RECOVERY_KEY_FILE="$test_dir/recovery.key" \
    RECOVERY_PUBLIC_KEY_FILE="$test_dir/recovery.pub" \
    scripts/demo-verify-recovery.sh >/dev/null 2>&1; then
  echo "Recovery verification accepted tampered ciphertext" >&2
  exit 1
fi

echo "Recovery key location, key format, key-pair, and tamper guards passed"
