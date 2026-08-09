#!/usr/bin/env bash
set -euo pipefail
umask 077

: "${RECOVERY_BUNDLE:?RECOVERY_BUNDLE is required}"
: "${RECOVERY_KEY_FILE:?RECOVERY_KEY_FILE is required}"
[[ -d "$RECOVERY_BUNDLE" ]] || { echo "Recovery bundle is missing" >&2; exit 1; }
[[ -s "$RECOVERY_KEY_FILE" ]] || { echo "Recovery key file is missing or empty" >&2; exit 1; }
key_mode=$(stat -f '%Lp' "$RECOVERY_KEY_FILE" 2>/dev/null || stat -c '%a' "$RECOVERY_KEY_FILE")
[[ "$key_mode" == "600" ]] || { echo "Recovery key file mode must be 600" >&2; exit 1; }
grep -Fqx 'recovery_verification=PENDING' "$RECOVERY_BUNDLE/recovery-receipt.env"
manifest_sha=$(shasum -a 256 "$RECOVERY_BUNDLE/SHA256SUMS" | awk '{print $1}')
grep -Fqx "bundle_manifest_sha256=$manifest_sha" "$RECOVERY_BUNDLE/recovery-receipt.env"
(cd "$RECOVERY_BUNDLE" && shasum -a 256 -c SHA256SUMS)

candidate_revision=$(awk -F= '$1 == "candidate_revision" {print $2}' "$RECOVERY_BUNDLE/recovery-receipt.env")
[[ "$candidate_revision" =~ ^[0-9a-f]{40}$ ]] || { echo "Invalid recovery candidate revision" >&2; exit 1; }
secret_dir=$(mktemp -d)
chmod 700 "$secret_dir"
suffix="$$-$(date -u +%s)"
scratch_mysql_volume="open-metadata-sync-live-recovery-mysql-$suffix"
scratch_jenkins_volume="open-metadata-sync-live-recovery-jenkins-$suffix"
scratch_network="open-metadata-sync-live-recovery-$suffix"
scratch_mysql="open-metadata-sync-live-recovery-mysql-$suffix"
cleanup() {
  docker rm -f "$scratch_mysql" >/dev/null 2>&1 || true
  docker volume rm "$scratch_mysql_volume" "$scratch_jenkins_volume" >/dev/null 2>&1 || true
  docker network rm "$scratch_network" >/dev/null 2>&1 || true
  rm -rf "${secret_dir:?}"
}
trap cleanup EXIT

decrypt_file() {
  local name=$1
  openssl enc -d -aes-256-cbc -pbkdf2 -md sha256 \
    -in "$RECOVERY_BUNDLE/$name.enc" -out "$secret_dir/$name" -pass file:"$RECOVERY_KEY_FILE"
  chmod 600 "$secret_dir/$name"
}
for name in mysql-password mysql-live-password mysql-root-password agent_ssh_key agent_ssh_key.pub \
  crossref-mailto jenkins-home.tar.gz live-and-replay.sql candidate-images.tar; do
  decrypt_file "$name"
done
replay_password=$(tr -d '\r\n' < "$secret_dir/mysql-password")
live_password=$(tr -d '\r\n' < "$secret_dir/mysql-live-password")
[[ "$replay_password" =~ ^[A-Za-z0-9_-]{32,128}$ ]] || { echo "Invalid replay password" >&2; exit 1; }
[[ "$live_password" =~ ^[A-Za-z0-9_-]{32,128}$ ]] || { echo "Invalid live password" >&2; exit 1; }

docker load -i "$secret_dir/candidate-images.tar" >/dev/null
candidate_images=(
  "open-metadata-sync-demo-controller:$candidate_revision"
  "open-metadata-sync-demo-agent:$candidate_revision"
  "open-metadata-sync-demo-gateway:$candidate_revision"
  "open-metadata-sync-demo-crossref-proxy:$candidate_revision"
)
cmp "$RECOVERY_BUNDLE/candidate-images-inspect.json" <(docker image inspect "${candidate_images[@]}")
docker network create "$scratch_network" >/dev/null
docker volume create "$scratch_mysql_volume" >/dev/null
docker volume create "$scratch_jenkins_volume" >/dev/null
docker run -d --name "$scratch_mysql" --network "$scratch_network" --network-alias mysql \
  -e MYSQL_ROOT_PASSWORD_FILE=/run/secrets/root \
  -v "$secret_dir/mysql-root-password:/run/secrets/root:ro" \
  -v "$scratch_mysql_volume:/var/lib/mysql" \
  mysql:8.4.10@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6 >/dev/null
for _ in {1..60}; do
  if docker exec "$scratch_mysql" /bin/bash -c '
      MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
      export MYSQL_PWD
      exec mysql --batch --skip-column-names -uroot -e "SELECT 1"
    ' >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
docker exec "$scratch_mysql" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
  export MYSQL_PWD
  exec mysql --batch --skip-column-names -uroot -e "SELECT 1"
' >/dev/null
docker exec -i "$scratch_mysql" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
  export MYSQL_PWD
  exec mysql -uroot
' < "$secret_dir/live-and-replay.sql"
docker exec -i "$scratch_mysql" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
  export MYSQL_PWD
  exec mysql -uroot
' <<SQL
CREATE USER IF NOT EXISTS 'open_metadata'@'%' IDENTIFIED BY '${replay_password}';
GRANT ALL PRIVILEGES ON open_metadata.* TO 'open_metadata'@'%';
CREATE USER IF NOT EXISTS 'open_metadata_live_demo'@'%' IDENTIFIED BY '${live_password}';
GRANT ALL PRIVILEGES ON open_metadata_live_demo.* TO 'open_metadata_live_demo'@'%';
SQL

scratch_root_query() {
  docker exec "$scratch_mysql" /bin/bash -c '
    MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
    export MYSQL_PWD
    exec mysql --batch --skip-column-names -uroot -e "$1"
  ' _ "$1"
}
schema_hash() {
  local schema=$1
  scratch_root_query "SET SESSION group_concat_max_len=1000000; SELECT GROUP_CONCAT(CONCAT(TABLE_NAME, '|', COLUMN_NAME, '|', COLUMN_TYPE, '|', IS_NULLABLE, '|', COLUMN_KEY, '|', EXTRA) ORDER BY TABLE_NAME, ORDINAL_POSITION SEPARATOR '\\n') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '$schema';" \
    | shasum -a 256 | awk '{print $1}'
}
data_hash() {
  local schema=$1
  docker exec "$scratch_mysql" /bin/bash -c '
    MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
    export MYSQL_PWD
    exec mysqldump -uroot --single-transaction --skip-comments --compact \
      --no-create-info --skip-triggers "$1"
  ' _ "$schema" | shasum -a 256 | awk '{print $1}'
}
verify_schema() {
  local label=$1
  local schema=$2
  local actual_schema actual_data actual_tables
  actual_schema=$(schema_hash "$schema")
  actual_data=$(data_hash "$schema")
  actual_tables=$(scratch_root_query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$schema';")
  grep -Fqx "${label}_schema_sha256=$actual_schema" "$RECOVERY_BUNDLE/recovery-receipt.env" || {
    echo "Scratch $label schema mismatch" >&2
    exit 1
  }
  grep -Fqx "${label}_data_sha256=$actual_data" "$RECOVERY_BUNDLE/recovery-receipt.env" || {
    echo "Scratch $label data mismatch" >&2
    exit 1
  }
  grep -Fqx "${label}_table_count=$actual_tables" "$RECOVERY_BUNDLE/recovery-receipt.env" || {
    echo "Scratch $label table count mismatch" >&2
    exit 1
  }
}
verify_schema live open_metadata_live_demo
verify_schema replay open_metadata

docker run --rm --entrypoint /bin/tar \
  -v "$scratch_jenkins_volume:/target" -v "$secret_dir:/backup:ro" \
  "open-metadata-sync-demo-controller:$candidate_revision" \
  -C /target -xzf /backup/jenkins-home.tar.gz
docker run --rm --entrypoint /bin/bash -v "$scratch_jenkins_volume:/target:ro" \
  "open-metadata-sync-demo-controller:$candidate_revision" -c '
    test -s /target/jobs/open-metadata-sync-demo-10k/config.xml
    test -s /target/jobs/open-metadata-sync-demo-replay/config.xml
  '
cmp "$RECOVERY_BUNDLE/mysql-volume-inspect.json" \
  <(docker volume inspect open-metadata-sync-public-demo-mysql-data)
cmp "$RECOVERY_BUNDLE/jenkins-volume-inspect.json" \
  <(docker volume inspect open-metadata-sync-public-demo-jenkins-home)

receipt_tmp="$RECOVERY_BUNDLE/recovery-receipt.env.tmp"
sed 's/^recovery_verification=PENDING$/recovery_verification=PASS/' \
  "$RECOVERY_BUNDLE/recovery-receipt.env" > "$receipt_tmp"
printf 'verified_at=%s\nrecovery_live_schema=PASS\nrecovery_replay_schema=PASS\nrecovery_jenkins_home=PASS\nrecovery_candidate_images=PASS\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$receipt_tmp"
mv "$receipt_tmp" "$RECOVERY_BUNDLE/recovery-receipt.env"
echo "Current live demo recovery bundle passed scratch restore verification"
