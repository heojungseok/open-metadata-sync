#!/usr/bin/env bash
set -euo pipefail

: "${RECOVERY_BUNDLE:?RECOVERY_BUNDLE is required}"
[[ -d "$RECOVERY_BUNDLE" ]] || { echo "Recovery bundle is missing" >&2; exit 1; }
[[ -s .demo-secrets/mysql-password ]] || { echo "Replay DB secret is missing" >&2; exit 1; }

(
  cd "$RECOVERY_BUNDLE"
  shasum -a 256 -c SHA256SUMS
)
docker load -i "$RECOVERY_BUNDLE/old-demo-images.tar" >/dev/null

suffix="$$-$(date -u +%s)"
scratch_volume="open-metadata-sync-recovery-$suffix"
scratch_network="open-metadata-sync-recovery-$suffix"
scratch_mysql="open-metadata-sync-recovery-mysql-$suffix"
root_password=$(openssl rand -hex 32)
replay_password=$(tr -d '\r\n' < .demo-secrets/mysql-password)

cleanup() {
  docker rm -f "$scratch_mysql" >/dev/null 2>&1 || true
  docker volume rm "$scratch_volume" >/dev/null 2>&1 || true
  docker network rm "$scratch_network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker network create "$scratch_network" >/dev/null
docker volume create "$scratch_volume" >/dev/null
docker run -d --name "$scratch_mysql" --network "$scratch_network" --network-alias mysql \
  -e MYSQL_ROOT_PASSWORD="$root_password" \
  -v "$scratch_volume:/var/lib/mysql" \
  mysql:8.4.10@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6 >/dev/null
for _ in {1..60}; do
  if docker exec -e MYSQL_PWD="$root_password" "$scratch_mysql" \
      mysql --batch --skip-column-names -uroot -e "SELECT 1" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
docker exec -e MYSQL_PWD="$root_password" "$scratch_mysql" \
  mysql --batch --skip-column-names -uroot -e "SELECT 1" >/dev/null
docker exec -i -e MYSQL_PWD="$root_password" "$scratch_mysql" mysql -uroot \
  < "$RECOVERY_BUNDLE/replay-and-legacy.sql"
docker exec -i -e MYSQL_PWD="$root_password" "$scratch_mysql" mysql -uroot <<SQL
CREATE USER IF NOT EXISTS 'open_metadata'@'%' IDENTIFIED BY '${replay_password}';
ALTER USER 'open_metadata'@'%' IDENTIFIED BY '${replay_password}';
GRANT ALL PRIVILEGES ON open_metadata.* TO 'open_metadata'@'%';
GRANT ALL PRIVILEGES ON open_metadata_benchmark_preflight.* TO 'open_metadata'@'%';
SQL

schemas=$(docker exec -e MYSQL_PWD="$root_password" "$scratch_mysql" mysql --batch --skip-column-names \
  -uroot information_schema -e "SELECT GROUP_CONCAT(SCHEMA_NAME ORDER BY SCHEMA_NAME) FROM SCHEMATA WHERE SCHEMA_NAME IN ('open_metadata','open_metadata_benchmark_preflight');")
[[ "$schemas" == "open_metadata,open_metadata_benchmark_preflight" ]] || {
  echo "Scratch restore schema verification failed" >&2
  exit 1
}

request_id="recovery-replay-$suffix"
docker run --rm --network "$scratch_network" --entrypoint /bin/bash \
  -e DEMO_RUNTIME=container -e DB_HOST=mysql -e DB_PORT=3306 \
  -e DB_USERNAME=open_metadata -e DB_PASSWORD="$replay_password" \
  -e DEMO_DB_SENTINEL_UUID=00000000-0000-0000-0000-00000000d000 \
  open-metadata-sync-demo-agent:47461be -c "
    set -euo pipefail
    scripts/demo-verify-10k-no-op-ready.sh
    export REQUEST_ID='$request_id'
    export SOURCE_EXECUTION_ID=00000000-0000-0000-0000-00000000d001
    export DEMO_REPLAY_RESET_ACK=REPLAY_ERRORS
    export DEMO_OUTPUT_DIR=/tmp/recovery-evidence
    scripts/demo-reset-replay.sh
    java -jar build/libs/open-metadata-sync-0.0.1-SNAPSHOT.jar \\
      --spring.batch.job.enabled=true --spring.batch.job.name=crossrefSyncJob \\
      --spring.profiles.active=actual \\
      requestId='$request_id',java.lang.String,true \\
      mode=REPLAY_ERRORS,java.lang.String,true \\
      sourceExecutionId=00000000-0000-0000-0000-00000000d001,java.lang.String,true \\
      chunkSize=1000,java.lang.Long,false hibernateBatchSize=1000,java.lang.Long,false
    scripts/demo-replay-summary.sh
  "

cmp "$RECOVERY_BUNDLE/mysql-volume-inspect.json" \
  <(docker volume inspect open-metadata-sync-public-demo-mysql-data)
cmp "$RECOVERY_BUNDLE/jenkins-volume-inspect.json" \
  <(docker volume inspect open-metadata-sync-public-demo-jenkins-home)
replay_schema=$(grep '^replay_schema_sha256=' "$RECOVERY_BUNDLE/recovery-receipt.env")
printf 'recovery_verification=PASS\n%s\nverified_at=%s\n' \
  "$replay_schema" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  > "$RECOVERY_BUNDLE/recovery-receipt.env"
echo "Recovery scratch restore and old demo/replay smoke passed"
