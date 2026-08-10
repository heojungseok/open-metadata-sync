#!/usr/bin/env bash
set -euo pipefail
umask 077

: "${RECOVERY_KEY_FILE:?RECOVERY_KEY_FILE is required}"
: "${RECOVERY_PUBLIC_KEY_FILE:?RECOVERY_PUBLIC_KEY_FILE is required}"
: "${CANDIDATE_REVISION:?CANDIDATE_REVISION is required}"
: "${LIVE_VALIDATION_RECEIPT_FILE:?LIVE_VALIDATION_RECEIPT_FILE is required}"
RECOVERY_ROOT=${RECOVERY_ROOT:-/Volumes/sd-128/open-metadata-sync/live-demo-recovery}
MYSQL_CONTAINER=open-metadata-sync-public-demo-mysql
MYSQL_VOLUME=open-metadata-sync-public-demo-mysql-data
JENKINS_VOLUME=open-metadata-sync-public-demo-jenkins-home
CANDIDATE_IMAGES=(
  "open-metadata-sync-demo-controller:$CANDIDATE_REVISION"
  "open-metadata-sync-demo-agent:$CANDIDATE_REVISION"
  "open-metadata-sync-demo-gateway:$CANDIDATE_REVISION"
  "open-metadata-sync-demo-crossref-proxy:$CANDIDATE_REVISION"
)
RUNTIME_CONTAINERS=(
  open-metadata-sync-public-demo-gateway
  open-metadata-sync-public-demo-controller
  open-metadata-sync-public-demo-agent
  open-metadata-sync-public-demo-crossref-proxy
)

[[ "${RECOVERY_EXPORT_ACK:-}" == "STOP_LIVE_RUNTIME_AND_EXPORT" ]] || {
  echo "Recovery export requires RECOVERY_EXPORT_ACK=STOP_LIVE_RUNTIME_AND_EXPORT" >&2
  exit 1
}
[[ "$CANDIDATE_REVISION" =~ ^[0-9a-f]{40}$ ]] || { echo "Invalid candidate revision" >&2; exit 1; }
[[ "$(git rev-parse HEAD)" == "$CANDIDATE_REVISION" ]] || { echo "Candidate revision is not checked out" >&2; exit 1; }
[[ -d "$RECOVERY_ROOT" ]] || { echo "Recovery root must already exist: $RECOVERY_ROOT" >&2; exit 1; }
[[ -s "$RECOVERY_KEY_FILE" ]] || { echo "Recovery key file is missing or empty" >&2; exit 1; }
[[ -s "$RECOVERY_PUBLIC_KEY_FILE" ]] || { echo "Recovery public key file is missing or empty" >&2; exit 1; }
key_mode=$(stat -f '%Lp' "$RECOVERY_KEY_FILE" 2>/dev/null || stat -c '%a' "$RECOVERY_KEY_FILE")
[[ "$key_mode" == "600" ]] || { echo "Recovery key file mode must be 600" >&2; exit 1; }
recovery_root_real=$(cd "$RECOVERY_ROOT" && pwd -P)
key_real=$(cd "$(dirname "$RECOVERY_KEY_FILE")" && printf '%s/%s\n' "$(pwd -P)" "$(basename "$RECOVERY_KEY_FILE")")
public_key_real=$(cd "$(dirname "$RECOVERY_PUBLIC_KEY_FILE")" && printf '%s/%s\n' "$(pwd -P)" "$(basename "$RECOVERY_PUBLIC_KEY_FILE")")
[[ "$key_real" != "$recovery_root_real" && "$key_real" != "$recovery_root_real/"* ]] || {
  echo "Recovery key must be stored outside the recovery root" >&2
  exit 1
}
[[ "$public_key_real" != "$recovery_root_real" && "$public_key_real" != "$recovery_root_real/"* ]] || {
  echo "Recovery public key must be stored outside the recovery root" >&2
  exit 1
}
openssl pkey -in "$RECOVERY_KEY_FILE" -noout >/dev/null 2>&1 || {
  echo "Recovery key must be an OpenSSL private key" >&2
  exit 1
}
openssl pkey -pubin -in "$RECOVERY_PUBLIC_KEY_FILE" -noout >/dev/null 2>&1 || {
  echo "Recovery public key must be an OpenSSL public key" >&2
  exit 1
}
cmp <(openssl pkey -in "$RECOVERY_KEY_FILE" -pubout 2>/dev/null) \
  <(openssl pkey -pubin -in "$RECOVERY_PUBLIC_KEY_FILE" -pubout 2>/dev/null) >/dev/null || {
  echo "Recovery private and public keys do not match" >&2
  exit 1
}
grep -Fqx 'live_demo_validation=PASS' "$LIVE_VALIDATION_RECEIPT_FILE"
grep -Fqx 'validation_scope=deployed' "$LIVE_VALIDATION_RECEIPT_FILE"
grep -Fqx "candidate_revision=$CANDIDATE_REVISION" "$LIVE_VALIDATION_RECEIPT_FILE"
for secret in mysql-password mysql-live-password mysql-root-password agent_ssh_key agent_ssh_key.pub crossref-mailto; do
  [[ -s ".demo-secrets/$secret" ]] || { echo "Demo secret is missing: $secret" >&2; exit 1; }
done
for image in "${CANDIDATE_IMAGES[@]}"; do
  docker image inspect "$image" >/dev/null
  [[ "$(docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image")" == "$CANDIDATE_REVISION" ]] || {
    echo "Candidate image label mismatch: $image" >&2
    exit 1
  }
done
for service in controller agent gateway crossref-proxy; do
  container="open-metadata-sync-public-demo-$service"
  image="open-metadata-sync-demo-$service:$CANDIDATE_REVISION"
  [[ "$(docker inspect -f '{{.State.Running}}' "$container")" == "true" ]] || {
    echo "Candidate runtime is not running: $container" >&2
    exit 1
  }
  [[ "$(docker inspect -f '{{.Image}}' "$container")" == "$(docker image inspect -f '{{.Id}}' "$image")" ]] || {
    echo "Candidate runtime image mismatch: $container" >&2
    exit 1
  }
done
for volume in "$MYSQL_VOLUME" "$JENKINS_VOLUME"; do
  docker volume inspect "$volume" >/dev/null
done
[[ "$(docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER")" == "true" ]] || {
  echo "Public demo MySQL must be running" >&2
  exit 1
}
mysql_volume_sha=$(docker volume inspect "$MYSQL_VOLUME" | shasum -a 256 | awk '{print $1}')
jenkins_volume_sha=$(docker volume inspect "$JENKINS_VOLUME" | shasum -a 256 | awk '{print $1}')
grep -Fqx "mysql_volume_inspect_sha256=$mysql_volume_sha" "$LIVE_VALIDATION_RECEIPT_FILE"
grep -Fqx "jenkins_volume_inspect_sha256=$jenkins_volume_sha" "$LIVE_VALIDATION_RECEIPT_FILE"

stamp=$(date -u +%Y%m%dT%H%M%SZ)
bundle="$RECOVERY_ROOT/$stamp"
mkdir -p "$bundle"
chmod 700 "$bundle"
[[ "$(stat -f '%Lp' "$bundle" 2>/dev/null || stat -c '%a' "$bundle")" == "700" ]] || {
  echo "Recovery bundle directory mode must be 700" >&2
  exit 1
}
runtime_stopped=0
wait_for_runtime() {
  for _ in {1..90}; do
    if curl --fail --silent http://127.0.0.1:9092/healthz >/dev/null 2>&1 \
        && docker exec open-metadata-sync-public-demo-gateway python3 -c "
import json, urllib.request
jobs=json.load(urllib.request.urlopen('http://jenkins-controller:8080/api/json?tree=jobs[name]', timeout=2))
nodes=json.load(urllib.request.urlopen('http://jenkins-controller:8080/computer/api/json?tree=computer[displayName,offline]', timeout=2))
assert {job['name'] for job in jobs['jobs']} == {'open-metadata-sync-demo-crossref'}
assert any(node['displayName'] == 'demo-agent' and not node['offline'] for node in nodes['computer'])
" >/dev/null 2>&1 \
        && docker exec open-metadata-sync-public-demo-agent python3 -c "
import urllib.request
urllib.request.urlopen('http://crossref-proxy:8080/healthz', timeout=2).read()
" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "Public demo runtime did not recover after export" >&2
  return 1
}
restart_runtime() {
  docker start open-metadata-sync-public-demo-crossref-proxy \
    open-metadata-sync-public-demo-agent open-metadata-sync-public-demo-controller \
    open-metadata-sync-public-demo-gateway >/dev/null
  wait_for_runtime
}
cleanup() {
  local status=$?
  trap - EXIT
  if [[ "$runtime_stopped" == "1" ]]; then
    if ! restart_runtime; then
      echo "Recovery export failed and the public runtime could not be restored" >&2
      status=1
    fi
  fi
  exit "$status"
}
trap cleanup EXIT

docker volume inspect "$MYSQL_VOLUME" > "$bundle/mysql-volume-inspect.json"
docker volume inspect "$JENKINS_VOLUME" > "$bundle/jenkins-volume-inspect.json"
docker image inspect "${CANDIDATE_IMAGES[@]}" > "$bundle/candidate-images-inspect.json"
git show "$CANDIDATE_REVISION:compose.always-on-demo.yaml" > "$bundle/candidate-compose.yaml"
install -m 600 "$LIVE_VALIDATION_RECEIPT_FILE" "$bundle/live-validation.env"

recovery_passphrase() {
  openssl pkey -in "$RECOVERY_KEY_FILE" -outform DER 2>/dev/null \
    | openssl dgst -sha256 -hex | awk '{print $2}'
}
encrypt_stream() {
  local name=$1
  openssl enc -aes-256-cbc -pbkdf2 -md sha256 -salt \
    -out "$bundle/$name.enc" -pass fd:3 3< <(recovery_passphrase)
}
for secret in mysql-password mysql-live-password mysql-root-password agent_ssh_key agent_ssh_key.pub crossref-mailto; do
  encrypt_stream "$secret" < ".demo-secrets/$secret"
done

docker stop "${RUNTIME_CONTAINERS[@]}" >/dev/null
runtime_stopped=1
docker run --rm --entrypoint /bin/tar \
  -v "$JENKINS_VOLUME:/source:ro" \
  "open-metadata-sync-demo-controller:$CANDIDATE_REVISION" \
  -C /source -czf - . | encrypt_stream jenkins-home.tar.gz
docker exec "$MYSQL_CONTAINER" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/demo_mysql_root_password)
  export MYSQL_PWD
  exec mysqldump -uroot --single-transaction --routines --triggers \
    --databases open_metadata open_metadata_live_demo
' | encrypt_stream live-and-replay.sql
docker save "${CANDIDATE_IMAGES[@]}" | encrypt_stream candidate-images.tar

root_query() {
  docker exec "$MYSQL_CONTAINER" /bin/bash -c '
    MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/demo_mysql_root_password)
    export MYSQL_PWD
    exec mysql --batch --skip-column-names -uroot -e "$1"
  ' _ "$1"
}
schema_hash() {
  local schema=$1
  root_query "SET SESSION group_concat_max_len=1000000; SELECT GROUP_CONCAT(CONCAT(TABLE_NAME, '|', COLUMN_NAME, '|', COLUMN_TYPE, '|', IS_NULLABLE, '|', COLUMN_KEY, '|', EXTRA) ORDER BY TABLE_NAME, ORDINAL_POSITION SEPARATOR '\\n') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '$schema';" \
    | shasum -a 256 | awk '{print $1}'
}
data_hash() {
  local schema=$1
  docker exec "$MYSQL_CONTAINER" /bin/bash -c '
    MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/demo_mysql_root_password)
    export MYSQL_PWD
    exec mysqldump -uroot --single-transaction --skip-comments --compact \
      --no-create-info --skip-triggers "$1"
  ' _ "$schema" | shasum -a 256 | awk '{print $1}'
}
live_schema=$(schema_hash open_metadata_live_demo)
live_data=$(data_hash open_metadata_live_demo)
live_tables=$(root_query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'open_metadata_live_demo';")
replay_schema=$(schema_hash open_metadata)
replay_data=$(data_hash open_metadata)
replay_tables=$(root_query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'open_metadata';")

if find "$bundle" -type f \( -name mysql-password -o -name mysql-live-password \
    -o -name mysql-root-password -o -name agent_ssh_key -o -name agent_ssh_key.pub \
    -o -name crossref-mailto -o -name jenkins-home.tar.gz -o -name live-and-replay.sql \
    -o -name candidate-images.tar \) | grep -q .; then
  echo "Plaintext recovery secret remained in bundle" >&2
  exit 1
fi

validation_sha=$(shasum -a 256 "$bundle/live-validation.env" | awk '{print $1}')
printf 'recovery_verification=PENDING\ncandidate_revision=%s\nlive_schema_sha256=%s\nlive_data_sha256=%s\nlive_table_count=%s\nreplay_schema_sha256=%s\nreplay_data_sha256=%s\nreplay_table_count=%s\nlive_validation_sha256=%s\n' \
  "$CANDIDATE_REVISION" "$live_schema" "$live_data" "$live_tables" \
  "$replay_schema" "$replay_data" "$replay_tables" "$validation_sha" \
  > "$bundle/recovery-receipt.env"
(
  cd "$bundle"
  shasum -a 256 candidate-images-inspect.json candidate-compose.yaml live-validation.env \
    mysql-volume-inspect.json jenkins-volume-inspect.json mysql-password.enc \
    mysql-live-password.enc mysql-root-password.enc agent_ssh_key.enc agent_ssh_key.pub.enc \
    crossref-mailto.enc jenkins-home.tar.gz.enc live-and-replay.sql.enc candidate-images.tar.enc \
    > SHA256SUMS
)
manifest_sha=$(shasum -a 256 "$bundle/SHA256SUMS" | awk '{print $1}')
printf 'bundle_manifest_sha256=%s\n' "$manifest_sha" >> "$bundle/recovery-receipt.env"
openssl pkeyutl -sign -rawin -inkey "$RECOVERY_KEY_FILE" \
  -in "$bundle/SHA256SUMS" -out "$bundle/SHA256SUMS.sig"
openssl pkeyutl -sign -rawin -inkey "$RECOVERY_KEY_FILE" \
  -in "$bundle/recovery-receipt.env" -out "$bundle/recovery-receipt.env.sig"

restart_runtime
runtime_stopped=0
printf '%s\n' "$bundle"
