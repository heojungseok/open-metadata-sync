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

stamp=$(date -u +%Y%m%dT%H%M%SZ)
bundle="$RECOVERY_ROOT/$stamp"
mkdir -p "$bundle"
root_password=$(tr -d '\r\n' < .demo-secrets/mysql-root-password)

docker volume inspect "$MYSQL_VOLUME" > "$bundle/mysql-volume-inspect.json"
docker volume inspect "$JENKINS_VOLUME" > "$bundle/jenkins-volume-inspect.json"
docker image inspect "${OLD_IMAGES[@]}" > "$bundle/old-images-inspect.json"
git rev-parse HEAD > "$bundle/export-revision.txt"

docker exec -e MYSQL_PWD="$root_password" "$MYSQL_CONTAINER" \
  mysqldump -uroot --single-transaction --routines --triggers \
  --databases open_metadata open_metadata_benchmark_preflight > "$bundle/replay-and-legacy.sql"
docker save -o "$bundle/old-demo-images.tar" "${OLD_IMAGES[@]}"

replay_schema=$(docker exec -e MYSQL_PWD="$root_password" "$MYSQL_CONTAINER" \
  mysql --batch --skip-column-names -uroot information_schema -e \
  "SET SESSION group_concat_max_len=1000000; SELECT GROUP_CONCAT(CONCAT(TABLE_NAME, '|', COLUMN_NAME, '|', COLUMN_TYPE, '|', IS_NULLABLE, '|', COLUMN_KEY, '|', EXTRA) ORDER BY TABLE_NAME, ORDINAL_POSITION SEPARATOR '\\n') FROM COLUMNS WHERE TABLE_SCHEMA = 'open_metadata';" \
  | shasum -a 256 | awk '{print $1}')
printf 'recovery_verification=PENDING\nreplay_schema_sha256=%s\n' "$replay_schema" \
  > "$bundle/recovery-receipt.env"

(
  cd "$bundle"
  shasum -a 256 replay-and-legacy.sql old-demo-images.tar mysql-volume-inspect.json \
    jenkins-volume-inspect.json old-images-inspect.json export-revision.txt > SHA256SUMS
)
printf '%s\n' "$bundle"
