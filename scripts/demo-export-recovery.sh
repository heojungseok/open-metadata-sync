#!/usr/bin/env bash
set -euo pipefail

RECOVERY_ROOT=${RECOVERY_ROOT:-/Volumes/sd-128/open-metadata-sync/2026-08-10-live-demo-cutover}
MYSQL_CONTAINER=open-metadata-sync-public-demo-mysql
MYSQL_VOLUME=open-metadata-sync-public-demo-mysql-data
JENKINS_VOLUME=open-metadata-sync-public-demo-jenkins-home
OLD_IMAGES=(
  open-metadata-sync-demo-controller:47461be
  open-metadata-sync-demo-agent:47461be
  open-metadata-sync-demo-gateway:47461be
)
[[ "${RECOVERY_EXPORT_ACK:-}" == "STOP_RUNTIME_AND_EXPORT" ]] || {
  echo "Recovery export requires RECOVERY_EXPORT_ACK=STOP_RUNTIME_AND_EXPORT" >&2
  exit 1
}

[[ -d "$RECOVERY_ROOT" ]] || {
  echo "Recovery root must already exist: $RECOVERY_ROOT" >&2
  exit 1
}
[[ -s .demo-secrets/mysql-root-password ]] || { echo "MySQL root secret is missing" >&2; exit 1; }
for image in "${OLD_IMAGES[@]}"; do
  docker image inspect "$image" >/dev/null
done
for volume in "$MYSQL_VOLUME" "$JENKINS_VOLUME"; do
  docker volume inspect "$volume" >/dev/null
done
docker stop open-metadata-sync-public-demo-gateway \
  open-metadata-sync-public-demo-controller open-metadata-sync-public-demo-agent >/dev/null
[[ "$(docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER")" == "true" ]] || {
  echo "Public demo MySQL must remain running for the consistent dump" >&2
  exit 1
}

stamp=$(date -u +%Y%m%dT%H%M%SZ)
bundle="$RECOVERY_ROOT/$stamp"
mkdir -p "$bundle"
docker volume inspect "$MYSQL_VOLUME" > "$bundle/mysql-volume-inspect.json"
docker volume inspect "$JENKINS_VOLUME" > "$bundle/jenkins-volume-inspect.json"
docker image inspect "${OLD_IMAGES[@]}" > "$bundle/old-images-inspect.json"
git rev-parse HEAD > "$bundle/export-revision.txt"
git show 83e0fab8757590222f827d99e58d497939eccd88:compose.always-on-demo.yaml \
  > "$bundle/legacy-compose.yaml"
install -m 600 .demo-secrets/mysql-password "$bundle/replay-password"
install -m 600 .demo-secrets/agent_ssh_key "$bundle/agent_ssh_key"
install -m 600 .demo-secrets/agent_ssh_key.pub "$bundle/agent_ssh_key.pub"
docker run --rm --entrypoint /bin/tar \
  -v "$JENKINS_VOLUME:/source:ro" -v "$bundle:/backup" \
  open-metadata-sync-demo-controller:47461be \
  -C /source -czf /backup/jenkins-home.tar.gz .

docker exec "$MYSQL_CONTAINER" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/demo_mysql_root_password)
  export MYSQL_PWD
  exec mysqldump -uroot --single-transaction --routines --triggers \
    --databases open_metadata open_metadata_benchmark_preflight
' > "$bundle/replay-and-legacy.sql"
docker save -o "$bundle/old-demo-images.tar" "${OLD_IMAGES[@]}"

replay_schema=$(docker exec "$MYSQL_CONTAINER" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/demo_mysql_root_password)
  export MYSQL_PWD
  exec mysql --batch --skip-column-names -uroot information_schema -e \
    "SET SESSION group_concat_max_len=1000000; SELECT GROUP_CONCAT(CONCAT(TABLE_NAME, '\''|'\'', COLUMN_NAME, '\''|'\'', COLUMN_TYPE, '\''|'\'', IS_NULLABLE, '\''|'\'', COLUMN_KEY, '\''|'\'', EXTRA) ORDER BY TABLE_NAME, ORDINAL_POSITION SEPARATOR '\''\\n'\'') FROM COLUMNS WHERE TABLE_SCHEMA = '\''open_metadata'\'';"
' \
  | shasum -a 256 | awk '{print $1}')
replay_data=$(docker exec "$MYSQL_CONTAINER" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/demo_mysql_root_password)
  export MYSQL_PWD
  exec mysqldump -uroot --single-transaction --skip-comments --compact \
    --no-create-info --skip-triggers open_metadata
' | shasum -a 256 | awk '{print $1}')
replay_table_count=$(docker exec "$MYSQL_CONTAINER" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/demo_mysql_root_password)
  export MYSQL_PWD
  exec mysql --batch --skip-column-names -uroot information_schema -e \
    "SELECT COUNT(*) FROM TABLES WHERE TABLE_SCHEMA = '\''open_metadata'\'';"
')
legacy_grant=$(docker exec "$MYSQL_CONTAINER" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/demo_mysql_root_password)
  export MYSQL_PWD
  exec mysql --batch --skip-column-names -uroot -e \
    "SELECT COUNT(*) FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE = CONCAT(CHAR(39), '\''open_metadata'\'', CHAR(39), '\''@'\'', CHAR(39), '\''%'\'', CHAR(39)) AND TABLE_SCHEMA = '\''open_metadata_benchmark_preflight'\'';"
')
printf 'recovery_verification=PENDING\ncandidate_revision=%s\nreplay_schema_sha256=%s\nreplay_data_sha256=%s\nreplay_table_count=%s\nlegacy_grant_count=%s\n' \
  "$(git rev-parse HEAD)" "$replay_schema" "$replay_data" "$replay_table_count" "$legacy_grant" \
  > "$bundle/recovery-receipt.env"

(
  cd "$bundle"
  shasum -a 256 replay-and-legacy.sql old-demo-images.tar mysql-volume-inspect.json \
    jenkins-volume-inspect.json old-images-inspect.json export-revision.txt jenkins-home.tar.gz \
    legacy-compose.yaml replay-password agent_ssh_key agent_ssh_key.pub > SHA256SUMS
)
manifest_sha=$(shasum -a 256 "$bundle/SHA256SUMS" | awk '{print $1}')
printf 'bundle_manifest_sha256=%s\n' "$manifest_sha" >> "$bundle/recovery-receipt.env"
printf '%s\n' "$bundle"
