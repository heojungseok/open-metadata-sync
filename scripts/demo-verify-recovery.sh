#!/usr/bin/env bash
set -euo pipefail

: "${RECOVERY_BUNDLE:?RECOVERY_BUNDLE is required}"
[[ -d "$RECOVERY_BUNDLE" ]] || { echo "Recovery bundle is missing" >&2; exit 1; }

(
  cd "$RECOVERY_BUNDLE"
  shasum -a 256 -c SHA256SUMS
)
docker load -i "$RECOVERY_BUNDLE/old-demo-images.tar" >/dev/null
cmp "$RECOVERY_BUNDLE/old-images-inspect.json" \
  <(docker image inspect open-metadata-sync-demo-controller:47461be \
      open-metadata-sync-demo-agent:47461be open-metadata-sync-demo-gateway:47461be)

suffix="$$-$(date -u +%s)"
scratch_mysql_volume="open-metadata-sync-recovery-mysql-$suffix"
scratch_jenkins_volume="open-metadata-sync-recovery-jenkins-$suffix"
scratch_network="open-metadata-sync-recovery-$suffix"
scratch_mysql="open-metadata-sync-recovery-mysql-$suffix"
scratch_agent="open-metadata-sync-recovery-agent-$suffix"
scratch_controller="open-metadata-sync-recovery-controller-$suffix"
scratch_gateway="open-metadata-sync-recovery-gateway-$suffix"
secret_dir=$(mktemp -d)
openssl rand -hex 32 > "$secret_dir/root"
chmod 600 "$secret_dir/root"
replay_password=$(tr -d '\r\n' < "$RECOVERY_BUNDLE/replay-password")

cleanup() {
  docker rm -f "$scratch_gateway" "$scratch_controller" "$scratch_agent" "$scratch_mysql" >/dev/null 2>&1 || true
  docker volume rm "$scratch_mysql_volume" "$scratch_jenkins_volume" >/dev/null 2>&1 || true
  docker network rm "$scratch_network" >/dev/null 2>&1 || true
  rm -rf "$secret_dir"
}
trap cleanup EXIT

docker network create "$scratch_network" >/dev/null
docker volume create "$scratch_mysql_volume" >/dev/null
docker volume create "$scratch_jenkins_volume" >/dev/null
docker run -d --name "$scratch_mysql" --network "$scratch_network" --network-alias mysql \
  -e MYSQL_ROOT_PASSWORD_FILE=/run/secrets/root \
  -v "$secret_dir/root:/run/secrets/root:ro" \
  -v "$scratch_mysql_volume:/var/lib/mysql" \
  mysql:8.4.10@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6 >/dev/null
for _ in {1..60}; do
  if docker exec "$scratch_mysql" /bin/bash -c '
      MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root) mysql --batch --skip-column-names -uroot -e "SELECT 1"
    ' >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
docker exec "$scratch_mysql" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root) mysql --batch --skip-column-names -uroot -e "SELECT 1"
' >/dev/null
docker exec -i "$scratch_mysql" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root) mysql -uroot
' \
  < "$RECOVERY_BUNDLE/replay-and-legacy.sql"
docker exec -i "$scratch_mysql" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root) mysql -uroot
' <<SQL
CREATE USER IF NOT EXISTS 'open_metadata'@'%' IDENTIFIED BY '${replay_password}';
ALTER USER 'open_metadata'@'%' IDENTIFIED BY '${replay_password}';
GRANT ALL PRIVILEGES ON open_metadata.* TO 'open_metadata'@'%';
GRANT ALL PRIVILEGES ON open_metadata_benchmark_preflight.* TO 'open_metadata'@'%';
SQL

schemas=$(docker exec "$scratch_mysql" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
  export MYSQL_PWD
  mysql --batch --skip-column-names -uroot information_schema -e \
    "SELECT GROUP_CONCAT(SCHEMA_NAME ORDER BY SCHEMA_NAME) FROM SCHEMATA WHERE SCHEMA_NAME IN ('\''open_metadata'\'','\''open_metadata_benchmark_preflight'\'');"
')
[[ "$schemas" == "open_metadata,open_metadata_benchmark_preflight" ]] || {
  echo "Scratch restore schema verification failed" >&2
  exit 1
}
scratch_root_query() {
  docker exec "$scratch_mysql" /bin/bash -c '
    MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
    export MYSQL_PWD
    exec mysql --batch --skip-column-names -uroot -e "$1"
  ' _ "$1"
}
replay_schema=$(scratch_root_query "SET SESSION group_concat_max_len=1000000; SELECT GROUP_CONCAT(CONCAT(TABLE_NAME, '|', COLUMN_NAME, '|', COLUMN_TYPE, '|', IS_NULLABLE, '|', COLUMN_KEY, '|', EXTRA) ORDER BY TABLE_NAME, ORDINAL_POSITION SEPARATOR '\\n') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'open_metadata';" | shasum -a 256 | awk '{print $1}')
grep -Fqx "replay_schema_sha256=$replay_schema" "$RECOVERY_BUNDLE/recovery-receipt.env" || {
  echo "Scratch replay schema mismatch" >&2
  exit 1
}
replay_data=$(docker exec "$scratch_mysql" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
  export MYSQL_PWD
  exec mysqldump -uroot --single-transaction --skip-comments --compact \
    --no-create-info --skip-triggers open_metadata
' | shasum -a 256 | awk '{print $1}')
grep -Fqx "replay_data_sha256=$replay_data" "$RECOVERY_BUNDLE/recovery-receipt.env" || {
  echo "Scratch replay data mismatch" >&2
  exit 1
}
replay_table_count=$(scratch_root_query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'open_metadata';")
grep -Fqx "replay_table_count=$replay_table_count" "$RECOVERY_BUNDLE/recovery-receipt.env" || {
  echo "Scratch replay table count mismatch" >&2
  exit 1
}
legacy_grant=$(scratch_root_query "SELECT COUNT(*) FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE = CONCAT(CHAR(39), 'open_metadata', CHAR(39), '@', CHAR(39), '%', CHAR(39)) AND TABLE_SCHEMA = 'open_metadata_benchmark_preflight';")
[[ "$legacy_grant" =~ ^[0-9]+$ && "$legacy_grant" -gt 0 ]] || {
  echo "Scratch legacy grant is absent" >&2
  exit 1
}
grep -Fqx "legacy_grant_count=$legacy_grant" "$RECOVERY_BUNDLE/recovery-receipt.env" || {
  echo "Scratch legacy grant mismatch" >&2
  exit 1
}

docker run --rm --entrypoint /bin/tar \
  -v "$scratch_jenkins_volume:/target" -v "$RECOVERY_BUNDLE:/backup:ro" \
  open-metadata-sync-demo-controller:47461be \
  -C /target -xzf /backup/jenkins-home.tar.gz

docker run -d --name "$scratch_agent" --network "$scratch_network" --network-alias jenkins-agent \
  --tmpfs /home/jenkins/agent:size=2g,exec,uid=1000,gid=1000,mode=0700 \
  -v "$RECOVERY_BUNDLE/agent_ssh_key.pub:/run/secrets/agent_ssh_pubkey:ro" \
  open-metadata-sync-demo-agent:47461be >/dev/null
docker run -d --name "$scratch_controller" --network "$scratch_network" --network-alias jenkins-controller \
  -v "$scratch_jenkins_volume:/var/jenkins_home" \
  -v "$RECOVERY_BUNDLE/agent_ssh_key:/run/secrets/agent_ssh_key:ro" \
  -v "$RECOVERY_BUNDLE/replay-password:/run/secrets/demo_mysql_password:ro" \
  open-metadata-sync-demo-controller:47461be >/dev/null
docker run -d --name "$scratch_gateway" --network "$scratch_network" \
  -e JENKINS_ORIGIN=http://jenkins-controller:8080 \
  open-metadata-sync-demo-gateway:47461be >/dev/null

for _ in {1..90}; do
  if docker exec "$scratch_gateway" python3 -c "
import json, urllib.request
urllib.request.urlopen('http://127.0.0.1:8080/healthz', timeout=2).read()
nodes=json.load(urllib.request.urlopen('http://jenkins-controller:8080/computer/api/json?tree=computer[displayName,offline]', timeout=2))
assert any(node['displayName'] == 'demo-agent' and not node['offline'] for node in nodes['computer'])
" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
docker exec "$scratch_gateway" python3 -c "
import json, urllib.request
nodes=json.load(urllib.request.urlopen('http://jenkins-controller:8080/computer/api/json?tree=computer[displayName,offline]', timeout=2))
assert any(node['displayName'] == 'demo-agent' and not node['offline'] for node in nodes['computer'])
"

last_build_number() {
  local job=$1
  docker exec "$scratch_gateway" python3 -c "
import json, urllib.request
data=json.load(urllib.request.urlopen('http://jenkins-controller:8080/job/$job/api/json?tree=lastBuild[number]', timeout=2))
print((data.get('lastBuild') or {}).get('number', 0))
"
}

trigger_and_wait() {
  local job=$1
  local body=$2
  local before
  before=$(last_build_number "$job")
  docker exec "$scratch_gateway" python3 -c "
import urllib.request
request=urllib.request.Request('http://127.0.0.1:8080/job/$job/buildWithParameters', data='$body'.encode(), method='POST')
with urllib.request.urlopen(request, timeout=10) as response:
    assert response.status in (200, 201, 202)
"
  for _ in {1..600}; do
    result=$(docker exec "$scratch_gateway" python3 -c "
import json, urllib.request
data=json.load(urllib.request.urlopen('http://jenkins-controller:8080/job/$job/api/json?tree=lastBuild[number,building,result]', timeout=2))
build=data.get('lastBuild') or {}
print(build.get('number', 0), str(build.get('building', True)).lower(), build.get('result') or '-')
")
    read -r number building status <<< "$result"
    if [[ "$number" -gt "$before" && "$building" == "false" ]]; then
      [[ "$status" == "SUCCESS" ]] || { echo "$job recovery smoke failed: $status" >&2; exit 1; }
      return
    fi
    sleep 2
  done
  echo "$job recovery smoke timed out" >&2
  exit 1
}

trigger_and_wait open-metadata-sync-demo-10k 'DEMO_SCENARIO=INITIAL&CHUNK_SIZE=1000'
trigger_and_wait open-metadata-sync-demo-10k 'DEMO_SCENARIO=NO_OP&CHUNK_SIZE=1000'
trigger_and_wait open-metadata-sync-demo-replay ''

cmp "$RECOVERY_BUNDLE/mysql-volume-inspect.json" \
  <(docker volume inspect open-metadata-sync-public-demo-mysql-data)
cmp "$RECOVERY_BUNDLE/jenkins-volume-inspect.json" \
  <(docker volume inspect open-metadata-sync-public-demo-jenkins-home)
receipt_tmp="$RECOVERY_BUNDLE/recovery-receipt.env.tmp"
sed 's/^recovery_verification=PENDING$/recovery_verification=PASS/' \
  "$RECOVERY_BUNDLE/recovery-receipt.env" > "$receipt_tmp"
printf 'verified_at=%s\nrecovery_initial=SUCCESS\nrecovery_no_op=SUCCESS\nrecovery_replay=SUCCESS\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$receipt_tmp"
mv "$receipt_tmp" "$RECOVERY_BUNDLE/recovery-receipt.env"
echo "Recovery scratch MySQL/Jenkins INITIAL, NO_OP, and replay passed"
