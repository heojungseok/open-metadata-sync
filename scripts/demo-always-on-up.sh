#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"

: "${CANDIDATE_REVISION:?CANDIDATE_REVISION is required}"
[[ "$CANDIDATE_REVISION" =~ ^[0-9a-f]{40}$ ]] || {
  echo "Invalid candidate revision" >&2
  exit 1
}
canonical_repository=https://github.com/heojungseok/open-metadata-sync.git
assert_local_candidate() {
  [[ -z "$(git status --porcelain --untracked-files=all)" ]] || {
    echo "Cutover requires a clean candidate worktree" >&2
    exit 1
  }
  [[ "$(git rev-parse HEAD)" == "$CANDIDATE_REVISION" ]] || {
    echo "Candidate revision is not checked out" >&2
    exit 1
  }
}
assert_live_main_candidate() {
  local live_main_revision
  live_main_revision=$(git ls-remote "$canonical_repository" refs/heads/main | awk 'NR == 1 {print $1}')
  [[ "$live_main_revision" =~ ^[0-9a-f]{40}$ ]] || {
    echo "Canonical main revision is unavailable" >&2
    exit 1
  }
  [[ "$live_main_revision" == "$CANDIDATE_REVISION" ]] || {
    echo "Candidate revision is not the live canonical main revision" >&2
    exit 1
  }
}
assert_candidate() {
  assert_local_candidate
  assert_live_main_candidate
}
assert_local_candidate
DEMO_INFRA_REVISION="$CANDIDATE_REVISION"
DEMO_IMAGE_TAG="$DEMO_INFRA_REVISION"
export DEMO_INFRA_REVISION DEMO_IMAGE_TAG

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
if [[ ! -f .demo-secrets/jenkins-admin-password ]]; then
  openssl rand -hex 32 > .demo-secrets/jenkins-admin-password
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
for service in mysql controller agent gateway crossref-proxy; do
  docker ps -a --filter "name=^/open-metadata-sync-public-demo-$service$" --format '{{.ID}}' \
    > "build/demo/$service-container-before.txt"
done

existing_components=0
for volume in open-metadata-sync-public-demo-mysql-data open-metadata-sync-public-demo-jenkins-home; do
  [[ "$(cat "build/demo/$volume-before.sha256")" == "not-created-yet" ]] || ((existing_components += 1))
done
for service in mysql controller agent gateway crossref-proxy; do
  [[ ! -s "build/demo/$service-container-before.txt" ]] || ((existing_components += 1))
done
if (( existing_components != 0 && existing_components != 7 )); then
  echo "Existing public demo runtime is incomplete" >&2
  exit 1
fi
existing_runtime=0
if (( existing_components == 7 )); then
  existing_runtime=1
  for service in mysql controller agent gateway crossref-proxy; do
    [[ "$(docker inspect -f '{{.State.Running}}' "open-metadata-sync-public-demo-$service")" == "true" ]] || {
      echo "Existing public demo runtime is not fully running: $service" >&2
      exit 1
    }
  done
  [[ "$(docker inspect -f '{{.State.Health.Status}}' open-metadata-sync-public-demo-mysql)" == "healthy" ]] || {
    echo "Existing public demo MySQL is not healthy" >&2
    exit 1
  }
  previous_infra_revision=
  for service in controller agent gateway crossref-proxy; do
    image_id=$(docker inspect -f '{{.Image}}' "open-metadata-sync-public-demo-$service")
    revision=$(docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image_id")
    [[ "$revision" =~ ^[0-9a-f]{40}$ ]] || { echo "Invalid previous image revision: $service" >&2; exit 1; }
    [[ -z "$previous_infra_revision" || "$revision" == "$previous_infra_revision" ]] || {
      echo "Previous custom image revisions are inconsistent" >&2
      exit 1
    }
    previous_infra_revision=$revision
    printf '%s\n' "$image_id" > "build/demo/$service-image-id-before.txt"
    docker image inspect "$image_id" > "build/demo/$service-image-before.json"
  done
  previous_source_revision=$(docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.source-revision"}}' \
    "$(cat build/demo/agent-image-id-before.txt)")
  [[ "$previous_source_revision" =~ ^[0-9a-f]{40}$ ]] || {
    echo "Invalid previous application source revision" >&2
    exit 1
  }
  printf '%s\n' "$previous_source_revision" > build/demo/agent-source-revision-before.txt
  if ! git diff --quiet "$previous_source_revision" "$CANDIDATE_REVISION" -- src/main/resources/db/migration; then
    echo "Candidate changes database migrations; separate data plan is required" >&2
    exit 1
  fi
fi

assert_candidate
docker compose -f compose.always-on-demo.yaml build jenkins-agent jenkins-controller gateway crossref-proxy
assert_candidate
gateway_stopped=0
cutover_started=0
restore_gateway_on_failure() {
  local status=$?
  trap - EXIT
  if [[ "$gateway_stopped" == "1" && "$cutover_started" == "0" ]]; then
    docker start open-metadata-sync-public-demo-gateway >/dev/null || status=1
  elif [[ "$cutover_started" == "1" ]]; then
    docker stop open-metadata-sync-public-demo-gateway >/dev/null 2>&1 || true
    local gateway_running
    gateway_running=$(docker inspect -f '{{.State.Running}}' open-metadata-sync-public-demo-gateway 2>/dev/null || true)
    if [[ "$gateway_running" == "true" ]]; then
      docker kill open-metadata-sync-public-demo-gateway >/dev/null 2>&1 || status=1
      gateway_running=$(docker inspect -f '{{.State.Running}}' open-metadata-sync-public-demo-gateway 2>/dev/null || true)
    fi
    if [[ "$gateway_running" == "true" ]]; then
      echo "Gateway remained running after fail-closed stop" >&2
      status=1
    elif [[ "$existing_runtime" == "1" ]]; then
      echo "Cutover failed closed; previous image evidence is in build/demo" >&2
    else
      echo "Initial deployment failed closed; inspect partial resources before retry" >&2
    fi
  fi
  exit "$status"
}
if [[ "$existing_runtime" == "1" ]]; then
  trap restore_gateway_on_failure EXIT
  docker stop open-metadata-sync-public-demo-gateway >/dev/null
  gateway_stopped=1
  CANDIDATE_REVISION="$DEMO_INFRA_REVISION" scripts/demo-assert-jenkins-quiescent.sh
fi
cutover_started=1
trap restore_gateway_on_failure EXIT
if [[ "$existing_runtime" == "1" ]]; then
  docker stop open-metadata-sync-public-demo-controller open-metadata-sync-public-demo-agent >/dev/null
fi
docker compose -f compose.always-on-demo.yaml up -d --no-recreate --wait mysql
docker compose -f compose.always-on-demo.yaml --profile bootstrap run --rm --no-deps live-db-bootstrap
docker compose -f compose.always-on-demo.yaml --profile bootstrap run --rm --no-deps live-migrate
docker compose -f compose.always-on-demo.yaml --profile bootstrap run --rm --no-deps replay-migrate
docker compose -f compose.always-on-demo.yaml --profile bootstrap run --rm --no-deps jenkins-home-bootstrap
docker compose -f compose.always-on-demo.yaml up -d --no-deps --force-recreate --wait --wait-timeout 60 crossref-proxy
docker compose -f compose.always-on-demo.yaml up -d --no-deps --force-recreate jenkins-agent
docker compose -f compose.always-on-demo.yaml up -d --no-deps --force-recreate jenkins-controller
docker compose -f compose.always-on-demo.yaml up -d --no-deps --force-recreate gateway

verify_owner() {
  python3 docker/demo-gateway/verify_owner_login.py http://127.0.0.1:9093 \
    < .demo-secrets/jenkins-admin-password
}

for _ in {1..60}; do
  if [[ "$(docker inspect -f '{{.State.Health.Status}}' open-metadata-sync-public-demo-crossref-proxy)" == "healthy" ]] \
      && curl --fail --silent http://127.0.0.1:9092/healthz >/dev/null \
      && docker compose -f compose.always-on-demo.yaml exec -T gateway python3 -c "
import json, urllib.request
job=json.load(urllib.request.urlopen('http://jenkins-controller:8080/job/open-metadata-sync-demo/api/json', timeout=2))
nodes=json.load(urllib.request.urlopen('http://jenkins-controller:8080/computer/api/json?tree=computer[displayName,offline]', timeout=2))
queue=json.load(urllib.request.urlopen('http://jenkins-controller:8080/queue/api/json?tree=items[id]', timeout=2))
assert job['name'] == 'open-metadata-sync-demo'
assert any(node['displayName'] == 'demo-agent' and not node['offline'] for node in nodes['computer'])
assert queue['items'] == []
" \
      && verify_owner \
      && CANDIDATE_REVISION="$DEMO_INFRA_REVISION" scripts/demo-assert-jenkins-quiescent.sh; then
    for volume in open-metadata-sync-public-demo-mysql-data open-metadata-sync-public-demo-jenkins-home; do
      docker volume inspect "$volume" > "build/demo/$volume-after.json"
      if [[ "$(cat "build/demo/$volume-before.sha256")" != "not-created-yet" ]]; then
        shasum -a 256 -c "build/demo/$volume-before.sha256"
        cmp "build/demo/$volume-before.json" "build/demo/$volume-after.json"
      fi
    done
    mysql_after=$(docker ps -a --filter "name=^/open-metadata-sync-public-demo-mysql$" --format '{{.ID}}')
    mysql_before=$(cat "build/demo/mysql-container-before.txt")
    [[ -n "$mysql_after" && ( -z "$mysql_before" || "$mysql_after" == "$mysql_before" ) ]] || {
      echo "MySQL container was unexpectedly recreated" >&2
      exit 1
    }
    for service in controller agent gateway crossref-proxy; do
      after=$(docker ps -a --filter "name=^/open-metadata-sync-public-demo-$service$" --format '{{.ID}}')
      before=$(cat "build/demo/$service-container-before.txt")
      [[ -n "$after" && "$after" != "$before" ]] || {
        echo "Runtime container was not recreated: $service" >&2
        exit 1
      }
    done
    gateway_stopped=0
    cutover_started=0
    trap - EXIT
    echo "Dedicated demo gateway is ready on http://127.0.0.1:9092"
    exit 0
  fi
  sleep 2
done
echo "Dedicated demo gateway did not become ready" >&2
exit 1
