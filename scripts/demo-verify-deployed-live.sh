#!/usr/bin/env bash
set -euo pipefail

: "${CANDIDATE_REVISION:?CANDIDATE_REVISION is required}"
: "${LIVE_REQUEST_ID:?LIVE_REQUEST_ID is required}"
: "${REPLAY_REQUEST_ID:?REPLAY_REQUEST_ID is required}"
: "${LIVE_VALIDATION_RECEIPT_FILE:?LIVE_VALIDATION_RECEIPT_FILE is required}"
: "${VISITOR_EVIDENCE_FILE:?VISITOR_EVIDENCE_FILE is required}"
[[ "$CANDIDATE_REVISION" =~ ^[0-9a-f]{40}$ ]] || { echo "Invalid candidate revision" >&2; exit 1; }
for request_id in "$LIVE_REQUEST_ID" "$REPLAY_REQUEST_ID"; do
  [[ "$request_id" =~ ^public-[0-9]+-[A-Za-z0-9_-]{8,32}$ ]] || {
    echo "Invalid public request ID: $request_id" >&2
    exit 1
  }
done
[[ -s "$VISITOR_EVIDENCE_FILE" ]] || { echo "Visitor evidence is missing" >&2; exit 1; }
evidence_value() {
  local key=$1
  awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; found=1} END {if (!found) exit 1}' \
    "$VISITOR_EVIDENCE_FILE"
}
visitor_path=$(evidence_value visitor_path)
otp_access=$(evidence_value otp_access)
public_hostname=$(evidence_value public_hostname)
evidence_live_request=$(evidence_value live_request_id)
live_cf_ray=$(evidence_value live_cf_ray)
evidence_replay_request=$(evidence_value replay_request_id)
replay_cf_ray=$(evidence_value replay_cf_ray)
live_chunk_size=$(evidence_value live_chunk_size)
[[ "$visitor_path" == "PASS" && "$otp_access" == "PASS" ]] || { echo "Visitor or OTP evidence failed" >&2; exit 1; }
[[ "$public_hostname" == "demo.heojungseok.com" ]] || { echo "Unexpected public hostname" >&2; exit 1; }
[[ "$evidence_live_request" == "$LIVE_REQUEST_ID" && "$evidence_replay_request" == "$REPLAY_REQUEST_ID" ]] || {
  echo "Visitor evidence request ID mismatch" >&2
  exit 1
}
for ray in "$live_cf_ray" "$replay_cf_ray"; do
  [[ "$ray" != "local" && "$ray" =~ ^[0-9A-Fa-f]{16,32}-[A-Z]{3}$ ]] || {
    echo "Invalid external CF-Ray evidence" >&2
    exit 1
  }
done
[[ "$live_chunk_size" =~ ^(100|500|1000|2000)$ ]] || { echo "Invalid live chunk evidence" >&2; exit 1; }
if [[ "${VALIDATE_VISITOR_EVIDENCE_ONLY:-0}" == "1" ]]; then
  echo "Visitor evidence validation passed"
  exit 0
fi
gateway_log=$(mktemp)
mysql_volume_inspect=$(mktemp)
jenkins_volume_inspect=$(mktemp)
trap 'rm -f "$gateway_log" "$mysql_volume_inspect" "$jenkins_volume_inspect"' EXIT
docker logs open-metadata-sync-public-demo-gateway > "$gateway_log" 2>&1
grep -F "ray=$live_cf_ray request_id=$LIVE_REQUEST_ID queued" "$gateway_log" >/dev/null
grep -F "ray=$replay_cf_ray request_id=$REPLAY_REQUEST_ID queued" "$gateway_log" >/dev/null

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
docker volume inspect open-metadata-sync-public-demo-mysql-data > "$mysql_volume_inspect"
docker volume inspect open-metadata-sync-public-demo-jenkins-home > "$jenkins_volume_inspect"
curl --fail --silent --show-error http://127.0.0.1:9092/healthz >/dev/null

verify_job() {
  local request_id=$1
  local mode=$2
  local chunk_size=$3
  docker exec -i open-metadata-sync-public-demo-gateway python3 - "$request_id" "$mode" "$chunk_size" <<'PY'
import json
import sys
import urllib.request

request_id, mode, chunk_size = sys.argv[1:]
tree = "builds[number,building,result,actions[parameters[name,value]],artifacts[fileName,relativePath]]{0,50}"
url = f"http://jenkins-controller:8080/job/open-metadata-sync-demo-crossref/api/json?tree={tree}"
with urllib.request.urlopen(url, timeout=5) as response:
    builds = json.load(response).get("builds", [])
expected = {"REQUEST_ID": request_id, "MODE": mode, "CHUNK_SIZE": chunk_size}
matching = []
for build in builds:
    parameters = {
        item["name"]: str(item.get("value", ""))
        for action in build.get("actions", [])
        for item in action.get("parameters", [])
    }
    if parameters == expected:
        matching.append(build)
assert len(matching) == 1, (matching, expected)
build = matching[0]
assert build["building"] is False, build
if mode == "BACKFILL":
    assert build["result"] == "SUCCESS", build
else:
    assert build["result"] in {"SUCCESS", "NOT_BUILT"}, build
artifacts = {item["fileName"] for item in build.get("artifacts", [])}
assert f"crossref-{request_id}.json" in artifacts, artifacts
print(f"{build['number']}|{build['result']}")
PY
}

live_build=$(verify_job "$LIVE_REQUEST_ID" BACKFILL "$live_chunk_size")
replay_build=$(verify_job "$REPLAY_REQUEST_ID" REPLAY_ERRORS 1000)
IFS='|' read -r live_build_number live_result <<< "$live_build"
IFS='|' read -r replay_build_number replay_result <<< "$replay_build"
summary=$(docker exec -i open-metadata-sync-public-demo-gateway python3 - "$LIVE_REQUEST_ID" "$live_build_number" <<'PY'
import json
import sys
import urllib.request

request_id, build_number = sys.argv[1:]
url = (
    f"http://jenkins-controller:8080/job/open-metadata-sync-demo-crossref/{build_number}/"
    f"artifact/build/jenkins/crossref-{request_id}.json"
)
with urllib.request.urlopen(url, timeout=5) as response:
    summary = json.load(response)
required = {
    "schema_version": 1,
    "request_id": request_id,
    "mode": "BACKFILL",
    "build_result": "SUCCESS",
    "expected_count": 10000,
    "staging_count": 10000,
    "accounted_count": 10000,
    "pages_fetched": 10,
    "total_open_errors": 0,
    "replayable_open_errors": 0,
    "business_status": "COMPLETED",
}
assert all(summary.get(key) == value for key, value in required.items()), summary
print(summary["sync_execution_id"])
PY
)
replay_reason=$(docker exec -i open-metadata-sync-public-demo-gateway python3 - "$REPLAY_REQUEST_ID" "$replay_build_number" "$replay_result" <<'PY'
import json
import sys
import urllib.request

request_id, build_number, build_result = sys.argv[1:]
url = (
    f"http://jenkins-controller:8080/job/open-metadata-sync-demo-crossref/{build_number}/"
    f"artifact/build/jenkins/crossref-{request_id}.json"
)
with urllib.request.urlopen(url, timeout=5) as response:
    summary = json.load(response)
assert summary["schema_version"] == 1, summary
assert summary["request_id"] == request_id and summary["mode"] == "REPLAY_ERRORS", summary
assert summary["build_result"] == build_result, summary
if build_result == "NOT_BUILT":
    assert summary["reason"] == "NO_REPLAY_TARGET", summary
    assert summary["source_execution_id"] is None and summary["replayable_open_errors"] == 0, summary
else:
    assert build_result == "SUCCESS" and summary["source_execution_id"], summary
print(summary["reason"])
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
visitor_sha=$(shasum -a 256 "$VISITOR_EVIDENCE_FILE" | awk '{print $1}')
mysql_volume_sha=$(shasum -a 256 "$mysql_volume_inspect" | awk '{print $1}')
jenkins_volume_sha=$(shasum -a 256 "$jenkins_volume_inspect" | awk '{print $1}')
printf 'live_demo_validation=PASS\nvalidation_scope=deployed\ncandidate_revision=%s\nvisitor_path=%s\notp_access=%s\npublic_hostname=%s\nlive_request_id=%s\nlive_cf_ray=%s\nlive_chunk_size=%s\nlive_build_number=%s\nlive_build_result=%s\nreplay_request_id=%s\nreplay_cf_ray=%s\nreplay_build_number=%s\nreplay_build_result=%s\nreplay_reason=%s\nsync_execution_id=%s\nreplay_schema_sha256=%s\nreplay_data_sha256=%s\nreplay_table_count=%s\nvisitor_evidence_sha256=%s\nmysql_volume_inspect_sha256=%s\njenkins_volume_inspect_sha256=%s\nverified_at=%s\n' \
  "$CANDIDATE_REVISION" "$visitor_path" "$otp_access" "$public_hostname" \
  "$LIVE_REQUEST_ID" "$live_cf_ray" "$live_chunk_size" "$live_build_number" "$live_result" \
  "$REPLAY_REQUEST_ID" "$replay_cf_ray" "$replay_build_number" "$replay_result" "$replay_reason" \
  "$summary" "$replay_schema" "$replay_data" "$replay_table_count" "$visitor_sha" \
  "$mysql_volume_sha" "$jenkins_volume_sha" \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$receipt_tmp"
mv "$receipt_tmp" "$LIVE_VALIDATION_RECEIPT_FILE"
echo "Deployed live 10K and replay validation passed"
