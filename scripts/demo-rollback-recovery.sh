#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"

: "${RECOVERY_BUNDLE:?RECOVERY_BUNDLE is required}"
: "${CANDIDATE_REVISION:?CANDIDATE_REVISION is required}"
[[ "${RECOVERY_ROLLBACK_ACK:-}" == "RESTORE_OLD_PUBLIC_DEMO" ]] || {
  echo "Rollback requires RECOVERY_ROLLBACK_ACK=RESTORE_OLD_PUBLIC_DEMO" >&2
  exit 1
}
[[ -s .demo-secrets/mysql-root-password ]] || { echo "Local MySQL root secret is missing" >&2; exit 1; }
[[ -s .demo-secrets/mysql-password ]] || { echo "Local replay secret is missing" >&2; exit 1; }
[[ -s .demo-secrets/agent_ssh_key ]] || { echo "Local agent key is missing" >&2; exit 1; }
grep -Fqx 'recovery_verification=PASS' "$RECOVERY_BUNDLE/recovery-receipt.env"
grep -Fqx "candidate_revision=$CANDIDATE_REVISION" "$RECOVERY_BUNDLE/recovery-receipt.env"
(cd "$RECOVERY_BUNDLE" && shasum -a 256 -c SHA256SUMS)
cmp "$RECOVERY_BUNDLE/mysql-volume-inspect.json" \
  <(docker volume inspect open-metadata-sync-public-demo-mysql-data)
cmp "$RECOVERY_BUNDLE/jenkins-volume-inspect.json" \
  <(docker volume inspect open-metadata-sync-public-demo-jenkins-home)

mysql_container=open-metadata-sync-public-demo-mysql
[[ "$(docker inspect -f '{{.State.Running}}' "$mysql_container")" == "true" ]] || {
  echo "Public MySQL must be running for pre-cleanup rollback validation" >&2
  exit 1
}
root_query() {
  docker exec "$mysql_container" /bin/bash -c '
    MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/demo_mysql_root_password)
    export MYSQL_PWD
    exec mysql --batch --skip-column-names -uroot -e "$1"
  ' _ "$1"
}
sentinel=$(root_query "SELECT CONCAT(environment_uuid, '|', environment_name) FROM open_metadata_benchmark_preflight.demo_environment_guard LIMIT 1;")
[[ "$sentinel" == '00000000-0000-0000-0000-00000000d000|open-metadata-sync-public-demo' ]] || {
  echo "Automatic rollback is allowed only before legacy cleanup" >&2
  exit 1
}
replay_sentinel=$(root_query "SELECT CONCAT(environment_uuid, '|', environment_name) FROM open_metadata.demo_environment_guard LIMIT 1;")
[[ "$replay_sentinel" == '00000000-0000-0000-0000-00000000d000|open-metadata-sync-public-demo' ]] || {
  echo "Replay schema sentinel mismatch" >&2
  exit 1
}
expected_replay_tables='BATCH_JOB_EXECUTION,BATCH_JOB_EXECUTION_CONTEXT,BATCH_JOB_EXECUTION_PARAMS,BATCH_JOB_EXECUTION_SEQ,BATCH_JOB_INSTANCE,BATCH_JOB_INSTANCE_SEQ,BATCH_STEP_EXECUTION,BATCH_STEP_EXECUTION_CONTEXT,BATCH_STEP_EXECUTION_SEQ,demo_environment_guard,flyway_schema_history,staging_work,sync_chunk_result,sync_error,sync_execution,sync_watermark,sync_window,work'
actual_replay_tables=$(root_query "SELECT GROUP_CONCAT(TABLE_NAME ORDER BY TABLE_NAME SEPARATOR ',') FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'open_metadata';")
[[ "$actual_replay_tables" == "$expected_replay_tables" ]] || {
  echo "Replay table contract mismatch" >&2
  exit 1
}
legacy_grant=$(root_query "SELECT COUNT(*) FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE = CONCAT(CHAR(39), 'open_metadata', CHAR(39), '@', CHAR(39), '%', CHAR(39)) AND TABLE_SCHEMA = 'open_metadata_benchmark_preflight';")
[[ "$legacy_grant" =~ ^[0-9]+$ && "$legacy_grant" -gt 0 ]] || {
  echo "Legacy grant is absent; automatic rollback is unsafe" >&2
  exit 1
}
grep -Fqx "legacy_grant_count=$legacy_grant" "$RECOVERY_BUNDLE/recovery-receipt.env"
replay_data=$(docker exec "$mysql_container" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/demo_mysql_root_password)
  export MYSQL_PWD
  exec mysqldump -uroot --single-transaction --skip-comments --compact \
    --no-create-info --skip-triggers open_metadata
' | shasum -a 256 | awk '{print $1}')

docker load -i "$RECOVERY_BUNDLE/old-demo-images.tar" >/dev/null
cmp "$RECOVERY_BUNDLE/old-images-inspect.json" \
  <(docker image inspect open-metadata-sync-demo-controller:47461be \
      open-metadata-sync-demo-agent:47461be open-metadata-sync-demo-gateway:47461be)
docker rm -f open-metadata-sync-public-demo-gateway \
  open-metadata-sync-public-demo-controller open-metadata-sync-public-demo-agent \
  open-metadata-sync-public-demo-crossref-proxy open-metadata-sync-public-demo-mysql >/dev/null
docker compose --project-directory "$PROJECT_DIR" -f "$RECOVERY_BUNDLE/legacy-compose.yaml" \
  up -d --force-recreate --remove-orphans mysql jenkins-agent jenkins-controller gateway

for _ in {1..90}; do
  if curl --fail --silent http://127.0.0.1:9092/healthz >/dev/null 2>&1 \
      && docker exec open-metadata-sync-public-demo-gateway python3 -c "
import json, urllib.request
job=json.load(urllib.request.urlopen('http://jenkins-controller:8080/job/open-metadata-sync-demo-10k/api/json', timeout=2))
nodes=json.load(urllib.request.urlopen('http://jenkins-controller:8080/computer/api/json?tree=computer[displayName,offline]', timeout=2))
assert job['name'] == 'open-metadata-sync-demo-10k'
assert any(node['displayName'] == 'demo-agent' and not node['offline'] for node in nodes['computer'])
" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

before=$(docker exec open-metadata-sync-public-demo-gateway python3 -c "
import json, urllib.request
data=json.load(urllib.request.urlopen('http://jenkins-controller:8080/job/open-metadata-sync-demo-10k/api/json?tree=lastBuild[number]', timeout=2))
print((data.get('lastBuild') or {}).get('number', 0))
")
docker exec open-metadata-sync-public-demo-gateway python3 -c "
import urllib.request
request=urllib.request.Request('http://127.0.0.1:8080/job/open-metadata-sync-demo-10k/buildWithParameters', data=b'DEMO_SCENARIO=NO_OP&CHUNK_SIZE=1000', method='POST')
with urllib.request.urlopen(request, timeout=10) as response:
    assert response.status in (200, 201, 202)
"
for _ in {1..600}; do
  result=$(docker exec open-metadata-sync-public-demo-gateway python3 -c "
import json, urllib.request
data=json.load(urllib.request.urlopen('http://jenkins-controller:8080/job/open-metadata-sync-demo-10k/api/json?tree=lastBuild[number,building,result]', timeout=2))
build=data.get('lastBuild') or {}
print(build.get('number', 0), str(build.get('building', True)).lower(), build.get('result') or '-')
")
  read -r number building status <<< "$result"
  if [[ "$number" -gt "$before" && "$building" == "false" ]]; then
    [[ "$status" == "SUCCESS" ]] || { echo "Rollback NO_OP smoke failed: $status" >&2; exit 1; }
    replay_data_after=$(docker exec "$mysql_container" /bin/bash -c '
      MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/demo_mysql_root_password)
      export MYSQL_PWD
      exec mysqldump -uroot --single-transaction --skip-comments --compact \
        --no-create-info --skip-triggers open_metadata
    ' | shasum -a 256 | awk '{print $1}')
    [[ "$replay_data_after" == "$replay_data" ]] || { echo "Replay changed during rollback" >&2; exit 1; }
    echo "Old public demo restored and NO_OP smoke passed"
    exit 0
  fi
  sleep 2
done
echo "Rollback NO_OP smoke timed out" >&2
exit 1
