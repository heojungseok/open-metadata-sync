#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"
[[ -z "$(git status --porcelain --untracked-files=all)" ]] || {
  echo "Full-stack E2E requires a clean candidate worktree" >&2
  exit 1
}
candidate=$(git rev-parse HEAD)
agent_image="open-metadata-sync-demo-agent:$candidate"
controller_image="open-metadata-sync-demo-controller:$candidate"
gateway_image="open-metadata-sync-demo-gateway:$candidate"
proxy_image="open-metadata-sync-demo-crossref-proxy:$candidate"
for image in "$agent_image" "$controller_image" "$gateway_image" "$proxy_image"; do
  [[ "$(docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image")" == "$candidate" ]] || {
    echo "Candidate image label mismatch: $image" >&2
    exit 1
  }
done

suffix="$$-$(date -u +%s)"
prefix="open-metadata-sync-live-e2e-$suffix"
data_network="$prefix-data"
app_network="$prefix-app"
provider_network="$prefix-provider"
mysql_volume="$prefix-mysql"
jenkins_volume="$prefix-jenkins"
mysql_container="$prefix-mysql"
stub_container="$prefix-stub"
proxy_container="$prefix-proxy"
agent_container="$prefix-agent"
controller_container="$prefix-controller"
gateway_container="$prefix-gateway"
secret_dir=$(mktemp -d)
original_dir=$(mktemp -d)
openssl rand -hex 32 > "$secret_dir/root"
openssl rand -hex 32 > "$secret_dir/replay"
openssl rand -hex 32 > "$secret_dir/live"
ssh-keygen -q -t ed25519 -N '' -C open-metadata-sync-live-e2e -f "$secret_dir/agent_ssh_key"
chmod 600 "$secret_dir"/*

for volume in open-metadata-sync-public-demo-mysql-data open-metadata-sync-public-demo-jenkins-home; do
  docker volume inspect "$volume" > "$original_dir/$volume.json"
done

cleanup() {
  docker rm -f "$gateway_container" "$controller_container" "$agent_container" \
    "$proxy_container" "$stub_container" "$mysql_container" >/dev/null 2>&1 || true
  docker volume rm "$mysql_volume" "$jenkins_volume" >/dev/null 2>&1 || true
  docker network rm "$provider_network" "$app_network" "$data_network" >/dev/null 2>&1 || true
  rm -rf "$secret_dir" "$original_dir"
}
trap cleanup EXIT

docker network create --internal "$data_network" >/dev/null
docker network create --internal "$app_network" >/dev/null
docker network create --internal "$provider_network" >/dev/null
docker volume create "$mysql_volume" >/dev/null
docker volume create "$jenkins_volume" >/dev/null
docker run --rm --user 0:0 --entrypoint /bin/bash \
  -v "$jenkins_volume:/var/jenkins_home" "$controller_image" -c '
    mkdir -p /var/jenkins_home/init.groovy.d
    printf "%s\n" "stale synthetic init" > /var/jenkins_home/init.groovy.d/security-and-jobs.groovy
    chown 501:20 /var/jenkins_home/init.groovy.d/security-and-jobs.groovy
  '
docker run --rm --user 0:0 --entrypoint /usr/local/bin/demo-bootstrap-jenkins-home \
  -v "$jenkins_volume:/var/jenkins_home" "$controller_image"
docker run -d --name "$mysql_container" --network "$data_network" --network-alias mysql \
  -e MYSQL_DATABASE=open_metadata -e MYSQL_USER=open_metadata \
  -e MYSQL_PASSWORD_FILE=/run/secrets/replay -e MYSQL_ROOT_PASSWORD_FILE=/run/secrets/root \
  -v "$secret_dir/replay:/run/secrets/replay:ro" -v "$secret_dir/root:/run/secrets/root:ro" \
  -v "$mysql_volume:/var/lib/mysql" \
  mysql:8.4.10@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6 >/dev/null
for _ in {1..60}; do
  if docker exec "$mysql_container" /bin/bash -c '
      MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root) mysql --batch --skip-column-names -uroot -e "SELECT 1"
    ' >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker exec "$mysql_container" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root) mysql --batch --skip-column-names -uroot -e "SELECT 1"
' >/dev/null

bootstrap_live() {
  docker run --rm --network "$data_network" --entrypoint /bin/bash \
    -e MYSQL_ROOT_PASSWORD_FILE=/run/secrets/root -e LIVE_DB_PASSWORD_FILE=/run/secrets/live \
    -e DEMO_BOOTSTRAP_ACK=CREATE_LIVE_DEMO \
    -v "$secret_dir/root:/run/secrets/root:ro" -v "$secret_dir/live:/run/secrets/live:ro" \
    -v "$PROJECT_DIR/scripts:/opt/demo/scripts:ro" \
    mysql:8.4.10@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6 \
    /opt/demo/scripts/demo-bootstrap-live-db.sh
}
bootstrap_live

docker run --rm --network "$data_network" --entrypoint /bin/bash \
  -e DB_HOST=mysql -e DB_PORT=3306 -e DB_NAME=open_metadata_live_demo \
  -e DB_USERNAME=open_metadata_live_demo -v "$secret_dir/live:/run/secrets/live:ro" \
  "$agent_image" -c '
    set -euo pipefail
    DB_PASSWORD=$(tr -d "\r\n" < /run/secrets/live); export DB_PASSWORD
    java -jar build/libs/open-metadata-sync-0.0.1-SNAPSHOT.jar --spring.batch.job.enabled=false --spring.profiles.active=actual
    MYSQL_PWD="$DB_PASSWORD" mysql --protocol=TCP -hmysql -P3306 -uopen_metadata_live_demo \
      open_metadata_live_demo < scripts/demo-install-live-sentinel.sql
  '
docker run --rm --network "$data_network" --entrypoint /bin/bash \
  -e DB_HOST=mysql -e DB_PORT=3306 -e DB_NAME=open_metadata -e DB_USERNAME=open_metadata \
  -v "$secret_dir/replay:/run/secrets/replay:ro" "$agent_image" -c '
    set -euo pipefail
    DB_PASSWORD=$(tr -d "\r\n" < /run/secrets/replay); export DB_PASSWORD
    java -jar build/libs/open-metadata-sync-0.0.1-SNAPSHOT.jar --spring.batch.job.enabled=false --spring.profiles.active=actual
    MYSQL_PWD="$DB_PASSWORD" mysql --protocol=TCP -hmysql -P3306 -uopen_metadata \
      open_metadata < scripts/demo-install-sentinel.sql
  '

start_stub() {
  local fail_page=$1
  docker run -d --name "$stub_container" --network "$provider_network" --network-alias crossref-stub \
    -e FAIL_PAGE="$fail_page" --entrypoint python3 "$proxy_image" /app/stub.py >/dev/null
}
start_stub 3
docker run -d --name "$proxy_container" --network "$provider_network" --network-alias crossref-proxy \
  --entrypoint python3 "$proxy_image" /app/e2e_proxy.py >/dev/null
for _ in {1..30}; do
  if docker exec "$proxy_container" python3 -c "
import urllib.request
urllib.request.urlopen('http://127.0.0.1:8080/healthz', timeout=2).read()
urllib.request.urlopen('http://crossref-stub:8080/metrics', timeout=2).read()
" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker exec "$proxy_container" python3 -c "
import urllib.request
urllib.request.urlopen('http://127.0.0.1:8080/healthz', timeout=2).read()
urllib.request.urlopen('http://crossref-stub:8080/metrics', timeout=2).read()
" >/dev/null
docker run -d --name "$agent_container" --network "$app_network" --network-alias jenkins-agent \
  --tmpfs /home/jenkins/agent:size=2g,exec,uid=1000,gid=1000,mode=0700 \
  -v "$secret_dir/agent_ssh_key.pub:/run/secrets/agent_ssh_pubkey:ro" "$agent_image" >/dev/null
docker network connect "$data_network" "$agent_container"
docker network connect "$provider_network" "$agent_container"

start_controller_gateway() {
  docker run -d --name "$controller_container" --network "$app_network" --network-alias jenkins-controller \
    -v "$jenkins_volume:/var/jenkins_home" \
    -v "$secret_dir/agent_ssh_key:/run/secrets/agent_ssh_key:ro" \
    -v "$secret_dir/replay:/run/secrets/demo_mysql_password:ro" \
    -v "$secret_dir/live:/run/secrets/demo_mysql_live_password:ro" \
    "$controller_image" >/dev/null
  docker run -d --name "$gateway_container" --network "$app_network" \
    -e JENKINS_ORIGIN=http://jenkins-controller:8080 "$gateway_image" >/dev/null
  for _ in {1..90}; do
    if docker exec "$gateway_container" python3 -c "
import json, urllib.request
nodes=json.load(urllib.request.urlopen('http://jenkins-controller:8080/computer/api/json?tree=computer[displayName,offline]', timeout=2))
assert any(node['displayName'] == 'demo-agent' and not node['offline'] for node in nodes['computer'])
" >/dev/null 2>&1; then
      return
    fi
    sleep 2
  done
  echo "Scratch Jenkins did not become ready" >&2
  exit 1
}
start_controller_gateway

last_build_number() {
  local job=$1
  docker exec "$gateway_container" python3 -c "
import json, urllib.request
data=json.load(urllib.request.urlopen('http://jenkins-controller:8080/job/$job/api/json?tree=lastBuild[number]', timeout=2))
print((data.get('lastBuild') or {}).get('number', 0))
"
}

trigger_and_wait() {
  local job=$1
  local body=$2
  local expected=$3
  local before request_id
  before=$(last_build_number "$job")
  request_id=$(docker exec "$gateway_container" python3 -c "
import urllib.request
request=urllib.request.Request('http://127.0.0.1:8080/job/$job/buildWithParameters', data='$body'.encode(),
    headers={'CF-Ray': 'abcdef1234567890-ICN'}, method='POST')
with urllib.request.urlopen(request, timeout=10) as response:
    print(response.headers['X-Demo-Request-Id'])
")
  for _ in {1..600}; do
    result=$(docker exec "$gateway_container" python3 -c "
import json, urllib.request
data=json.load(urllib.request.urlopen('http://jenkins-controller:8080/job/$job/api/json?tree=lastBuild[number,building,result]', timeout=2))
build=data.get('lastBuild') or {}
print(build.get('number', 0), str(build.get('building', True)).lower(), build.get('result') or '-')
")
    read -r number building status <<< "$result"
    if [[ "$number" -gt "$before" && "$building" == "false" ]]; then
      [[ "$status" == "$expected" ]] || { echo "$job expected $expected, got $status" >&2; exit 1; }
      printf '%s\n' "$request_id"
      return
    fi
    sleep 2
  done
  echo "$job E2E timed out" >&2
  exit 1
}

failed_request=$(trigger_and_wait open-metadata-sync-demo-10k 'CHUNK_SIZE=1000' FAILURE)
partial=$(docker exec "$mysql_container" /bin/bash -c "
  MYSQL_PWD=\$(tr -d '\\r\\n' < /run/secrets/root)
  export MYSQL_PWD
  mysql --batch --skip-column-names -uroot open_metadata_live_demo -e \
    \"SELECT COUNT(*) FROM staging_work WHERE execution_id = (SELECT id FROM sync_execution WHERE request_id = '$failed_request');\"
")
[[ "$partial" -gt 0 && "$partial" -lt 10000 ]] || { echo "Expected partial failed collection" >&2; exit 1; }

docker rm -f "$stub_container" >/dev/null
start_stub 0
old_live="$secret_dir/live-old"
mv "$secret_dir/live" "$old_live"
openssl rand -hex 32 > "$secret_dir/live"
chmod 600 "$secret_dir/live"
bootstrap_live
docker rm -f "$gateway_container" "$controller_container" >/dev/null
start_controller_gateway

echo "Waiting for the production 300-second provider cooldown in the preserved Jenkins history"
sleep 305
success_request=$(trigger_and_wait open-metadata-sync-demo-10k 'CHUNK_SIZE=1000' SUCCESS)
[[ "$success_request" != "$failed_request" ]] || { echo "Rerun request ID was reused" >&2; exit 1; }
replay_request=$(trigger_and_wait open-metadata-sync-demo-replay '' SUCCESS)
docker logs "$gateway_container" 2>&1 | grep -F \
  "ray=abcdef1234567890-ICN request_id=$success_request queued" >/dev/null
docker logs "$gateway_container" 2>&1 | grep -F \
  "ray=abcdef1234567890-ICN request_id=$replay_request queued" >/dev/null

docker exec "$stub_container" python3 -c "
import json, urllib.request
metrics=json.load(urllib.request.urlopen('http://127.0.0.1:8080/metrics', timeout=2))
assert metrics['pages'] == list(range(1, 11)), metrics
assert metrics['max_active'] == 1, metrics
intervals=[right-left for left, right in zip(metrics['started_at'], metrics['started_at'][1:])]
assert all(value >= 0.35 for value in intervals), intervals
"
docker exec "$agent_container" /bin/bash -c \
  "! timeout 3 bash -c 'exec 3<>/dev/tcp/api.crossref.org/443'" >/dev/null 2>&1

evidence=$(docker exec "$mysql_container" /bin/bash -c "
  MYSQL_PWD=\$(tr -d '\\r\\n' < /run/secrets/root)
  export MYSQL_PWD
  mysql --batch --skip-column-names -uroot open_metadata_live_demo -e \
    \"SELECT CONCAT(expected_count, '|', collection_pages_fetched, '|', collection_stop_reason, '|', (SELECT COUNT(*) FROM staging_work WHERE execution_id = execution.id), '|', business_status) FROM sync_execution execution WHERE request_id = '$success_request';\"
")
[[ "$evidence" == '10000|10|MAX_ITEMS|10000|COMPLETED' ]] || {
  echo "Live E2E reconciliation failed: $evidence" >&2
  exit 1
}

for volume in open-metadata-sync-public-demo-mysql-data open-metadata-sync-public-demo-jenkins-home; do
  cmp "$original_dir/$volume.json" <(docker volume inspect "$volume")
done
mkdir -p build/e2e
printf 'live_demo_validation=PASS\nvalidation_scope=local\ncandidate_revision=%s\nrequest_id=%s\nfailed_request_id=%s\n' \
  "$candidate" "$success_request" "$failed_request" > build/e2e/live-validation.env
echo "Gateway -> Jenkins -> agent -> proxy -> stub -> live MySQL E2E passed"
