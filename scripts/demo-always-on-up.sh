#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"

expected_revision=c38fa23ff126267bf97409a29c3f1c9d851b2492
if [[ -n "$(git status --porcelain --untracked-files=all)" ]]; then
  echo "Cutover requires a clean candidate worktree" >&2
  exit 1
fi
DEMO_INFRA_REVISION=$(git rev-parse HEAD)
DEMO_IMAGE_TAG="$DEMO_INFRA_REVISION"
export DEMO_INFRA_REVISION DEMO_IMAGE_TAG
if ! git diff --quiet "$expected_revision" -- build.gradle settings.gradle gradlew gradle src/main; then
  echo "Approved application source differs from $expected_revision" >&2
  exit 1
fi

umask 077
mkdir -p .demo-secrets
if [[ ! -f .demo-secrets/agent_ssh_key ]]; then
  ssh-keygen -q -t ed25519 -N '' -C open-metadata-sync-public-demo \
    -f .demo-secrets/agent_ssh_key
fi
if [[ ! -f .demo-secrets/mysql-password ]]; then
  openssl rand -hex 32 > .demo-secrets/mysql-password
fi
if [[ ! -f .demo-secrets/mysql-root-password ]]; then
  openssl rand -hex 32 > .demo-secrets/mysql-root-password
fi
if [[ ! -f .demo-secrets/mysql-live-password ]]; then
  openssl rand -hex 32 > .demo-secrets/mysql-live-password
fi
if [[ ! -s .demo-secrets/crossref-mailto ]]; then
  echo "Create .demo-secrets/crossref-mailto with the approved Crossref contact email" >&2
  exit 1
fi
chmod 600 .demo-secrets/*

mkdir -p build/demo
for volume in open-metadata-sync-public-demo-mysql-data open-metadata-sync-public-demo-jenkins-home; do
  if docker volume inspect "$volume" > "build/demo/$volume-before.json" 2>/dev/null; then
    shasum -a 256 "build/demo/$volume-before.json" > "build/demo/$volume-before.sha256"
  else
    printf 'not-created-yet\n' > "build/demo/$volume-before.sha256"
  fi
done
for service in mysql controller agent gateway; do
  docker ps -a --filter "name=^/open-metadata-sync-public-demo-$service$" --format '{{.ID}}' \
    > "build/demo/$service-container-before.txt"
done

docker compose -f compose.always-on-demo.yaml build jenkins-agent jenkins-controller gateway crossref-proxy
gateway_stopped=0
restore_gateway_on_failure() {
  local status=$?
  trap - EXIT
  if [[ "$gateway_stopped" == "1" ]]; then
    docker start open-metadata-sync-public-demo-gateway >/dev/null || status=1
  fi
  exit "$status"
}
if docker volume inspect open-metadata-sync-public-demo-jenkins-home >/dev/null 2>&1; then
  for service in controller agent gateway; do
    [[ "$(docker inspect -f '{{.State.Running}}' "open-metadata-sync-public-demo-$service")" == "true" ]] || {
      echo "Existing Jenkins runtime must be running and drainable before cutover: $service" >&2
      exit 1
    }
  done
  trap restore_gateway_on_failure EXIT
  docker stop open-metadata-sync-public-demo-gateway >/dev/null
  gateway_stopped=1
  CANDIDATE_REVISION="$DEMO_INFRA_REVISION" scripts/demo-assert-jenkins-quiescent.sh
  gateway_stopped=0
  trap - EXIT
fi
docker compose -f compose.always-on-demo.yaml down --remove-orphans
docker compose -f compose.always-on-demo.yaml up -d --force-recreate mysql
docker compose -f compose.always-on-demo.yaml --profile bootstrap run --rm live-db-bootstrap
docker compose -f compose.always-on-demo.yaml --profile bootstrap run --rm --no-deps live-migrate
docker compose -f compose.always-on-demo.yaml --profile bootstrap run --rm --no-deps replay-migrate
docker compose -f compose.always-on-demo.yaml --profile bootstrap run --rm --no-deps jenkins-home-bootstrap
docker compose -f compose.always-on-demo.yaml up -d --force-recreate crossref-proxy
docker compose -f compose.always-on-demo.yaml up -d --force-recreate jenkins-agent
docker compose -f compose.always-on-demo.yaml up -d --force-recreate jenkins-controller
docker compose -f compose.always-on-demo.yaml up -d --force-recreate gateway

for _ in {1..60}; do
  if curl --fail --silent http://127.0.0.1:9092/healthz >/dev/null \
      && docker compose -f compose.always-on-demo.yaml exec -T gateway python3 -c "
import json, urllib.request
job=json.load(urllib.request.urlopen('http://jenkins-controller:8080/job/open-metadata-sync-demo-crossref/api/json', timeout=2))
nodes=json.load(urllib.request.urlopen('http://jenkins-controller:8080/computer/api/json?tree=computer[displayName,offline]', timeout=2))
queue=json.load(urllib.request.urlopen('http://jenkins-controller:8080/queue/api/json?tree=items[id]', timeout=2))
assert job['name'] == 'open-metadata-sync-demo-crossref'
assert any(node['displayName'] == 'demo-agent' and not node['offline'] for node in nodes['computer'])
assert queue['items'] == []
" \
      && CANDIDATE_REVISION="$DEMO_INFRA_REVISION" scripts/demo-assert-jenkins-quiescent.sh \
      && docker compose -f compose.always-on-demo.yaml exec -T jenkins-controller /bin/bash -c '
        for job in open-metadata-sync-demo-10k open-metadata-sync-demo-replay; do
          config="/var/jenkins_home/jobs/$job/config.xml"
          [[ ! -e "$config" ]] || grep -Fq "<disabled>true</disabled>" "$config"
        done
      '; then
    for volume in open-metadata-sync-public-demo-mysql-data open-metadata-sync-public-demo-jenkins-home; do
      docker volume inspect "$volume" > "build/demo/$volume-after.json"
      if [[ "$(cat "build/demo/$volume-before.sha256")" != "not-created-yet" ]]; then
        shasum -a 256 -c "build/demo/$volume-before.sha256"
        cmp "build/demo/$volume-before.json" "build/demo/$volume-after.json"
      fi
    done
    for service in mysql controller agent gateway; do
      after=$(docker ps -a --filter "name=^/open-metadata-sync-public-demo-$service$" --format '{{.ID}}')
      before=$(cat "build/demo/$service-container-before.txt")
      [[ -n "$after" && "$after" != "$before" ]] || {
        echo "Runtime container was not recreated: $service" >&2
        exit 1
      }
    done
    echo "Dedicated demo gateway is ready on http://127.0.0.1:9092"
    exit 0
  fi
  sleep 2
done
echo "Dedicated demo gateway did not become ready" >&2
exit 1
