#!/usr/bin/env bash
set -euo pipefail

: "${RECOVERY_BUNDLE:?RECOVERY_BUNDLE is required}"
: "${CANDIDATE_REVISION:?CANDIDATE_REVISION is required}"
: "${LIVE_REQUEST_ID:?LIVE_REQUEST_ID is required}"
: "${REPLAY_REQUEST_ID:?REPLAY_REQUEST_ID is required}"
: "${LIVE_VALIDATION_RECEIPT_FILE:?LIVE_VALIDATION_RECEIPT_FILE is required}"
[[ "$CANDIDATE_REVISION" =~ ^[0-9a-f]{40}$ ]] || { echo "Invalid candidate revision" >&2; exit 1; }
for request_id in "$LIVE_REQUEST_ID" "$REPLAY_REQUEST_ID"; do
  [[ "$request_id" =~ ^public-[0-9]+-[A-Za-z0-9_-]{8,32}$ ]] || {
    echo "Invalid public request ID: $request_id" >&2
    exit 1
  }
done
[[ -d "$RECOVERY_BUNDLE" ]] || { echo "Recovery bundle is missing" >&2; exit 1; }
grep -Fqx 'recovery_verification=PASS' "$RECOVERY_BUNDLE/recovery-receipt.env"
grep -Fqx "candidate_revision=$CANDIDATE_REVISION" "$RECOVERY_BUNDLE/recovery-receipt.env"
(cd "$RECOVERY_BUNDLE" && shasum -a 256 -c SHA256SUMS)

verify_candidate_container() {
  local service=$1
  local image=$2
  local container="open-metadata-sync-public-demo-$service"
  [[ "$(docker inspect -f '{{.State.Running}}' "$container")" == "true" ]] || {
    echo "Public container is not running: $container" >&2
    exit 1
  }
  [[ "$(docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image")" == "$CANDIDATE_REVISION" ]] || {
    echo "Candidate image label mismatch: $image" >&2
    exit 1
  }
  [[ "$(docker inspect -f '{{.Image}}' "$container")" == "$(docker image inspect -f '{{.Id}}' "$image")" ]] || {
    echo "Public container image mismatch: $container" >&2
    exit 1
  }
}

verify_candidate_container controller "open-metadata-sync-demo-controller:$CANDIDATE_REVISION"
verify_candidate_container agent "open-metadata-sync-demo-agent:$CANDIDATE_REVISION"
verify_candidate_container gateway "open-metadata-sync-demo-gateway:$CANDIDATE_REVISION"
verify_candidate_container crossref-proxy "open-metadata-sync-demo-crossref-proxy:$CANDIDATE_REVISION"
[[ "$(docker inspect -f '{{.State.Running}}' open-metadata-sync-public-demo-mysql)" == "true" ]]
cmp "$RECOVERY_BUNDLE/mysql-volume-inspect.json" \
  <(docker volume inspect open-metadata-sync-public-demo-mysql-data)
cmp "$RECOVERY_BUNDLE/jenkins-volume-inspect.json" \
  <(docker volume inspect open-metadata-sync-public-demo-jenkins-home)
curl --fail --silent --show-error http://127.0.0.1:9092/healthz >/dev/null

verify_job() {
  local job=$1
  local request_id=$2
  docker exec -i open-metadata-sync-public-demo-gateway python3 - "$job" "$request_id" <<'PY'
import json
import sys
import urllib.request

job, request_id = sys.argv[1:]
base = f"http://jenkins-controller:8080/job/{job}/lastBuild"
tree = "number,building,result,actions[parameters[name,value]],artifacts[fileName,relativePath]"
with urllib.request.urlopen(f"{base}/api/json?tree={tree}", timeout=5) as response:
    build = json.load(response)
assert build["building"] is False and build["result"] == "SUCCESS", build
parameters = {
    item["name"]: str(item.get("value", ""))
    for action in build.get("actions", [])
    for item in action.get("parameters", [])
}
assert parameters.get("REQUEST_ID") == request_id, parameters
print(build["number"])
PY
}

live_build=$(verify_job open-metadata-sync-demo-10k "$LIVE_REQUEST_ID")
replay_build=$(verify_job open-metadata-sync-demo-replay "$REPLAY_REQUEST_ID")
summary=$(docker exec -i open-metadata-sync-public-demo-gateway python3 - "$LIVE_REQUEST_ID" <<'PY'
import json
import sys
import urllib.request

request_id = sys.argv[1]
url = (
    "http://jenkins-controller:8080/job/open-metadata-sync-demo-10k/lastSuccessfulBuild/"
    f"artifact/build/jenkins/live-crossref-{request_id}.json"
)
with urllib.request.urlopen(url, timeout=5) as response:
    summary = json.load(response)
required = {
    "schema_version": "6",
    "request_id": request_id,
    "expected_count": 10000,
    "staging_count": 10000,
    "accounted_count": 10000,
    "pages_fetched": 10,
    "checksum_mismatches": 0,
    "open_errors": 0,
    "status": "COMPLETED",
}
assert all(summary.get(key) == value for key, value in required.items()), summary
print(summary["sync_execution_id"])
PY
)
mysql_container=open-metadata-sync-public-demo-mysql
root_query() {
  docker exec "$mysql_container" /bin/bash -c '
    MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/demo_mysql_root_password)
    export MYSQL_PWD
    exec mysql --batch --skip-column-names -uroot -e "$1"
  ' _ "$1"
}
replay_schema=$(root_query "SET SESSION group_concat_max_len=1000000; SELECT GROUP_CONCAT(CONCAT(TABLE_NAME, '|', COLUMN_NAME, '|', COLUMN_TYPE, '|', IS_NULLABLE, '|', COLUMN_KEY, '|', EXTRA) ORDER BY TABLE_NAME, ORDINAL_POSITION SEPARATOR '\\n') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'open_metadata';" | shasum -a 256 | awk '{print $1}')
replay_table_count=$(root_query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'open_metadata';")
replay_data=$(docker exec "$mysql_container" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/demo_mysql_root_password)
  export MYSQL_PWD
  exec mysqldump -uroot --single-transaction --skip-comments --compact \
    --no-create-info --skip-triggers open_metadata
' | shasum -a 256 | awk '{print $1}')

receipt_tmp="$LIVE_VALIDATION_RECEIPT_FILE.tmp"
umask 077
mkdir -p "$(dirname "$LIVE_VALIDATION_RECEIPT_FILE")"
printf 'live_demo_validation=PASS\nvalidation_scope=deployed\ncandidate_revision=%s\nlive_request_id=%s\nlive_build_number=%s\nreplay_request_id=%s\nreplay_build_number=%s\nsync_execution_id=%s\nreplay_schema_sha256=%s\nreplay_data_sha256=%s\nreplay_table_count=%s\nverified_at=%s\n' \
  "$CANDIDATE_REVISION" "$LIVE_REQUEST_ID" "$live_build" "$REPLAY_REQUEST_ID" "$replay_build" \
  "$summary" "$replay_schema" "$replay_data" "$replay_table_count" \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$receipt_tmp"
mv "$receipt_tmp" "$LIVE_VALIDATION_RECEIPT_FILE"
echo "Deployed live 10K and replay validation passed"
