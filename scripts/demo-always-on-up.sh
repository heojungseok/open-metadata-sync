#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"

expected_revision=8e266d82c5305b5d0b870760c7adbd7b8c46498c
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

docker compose -f compose.always-on-demo.yaml build jenkins-agent jenkins-controller gateway crossref-proxy
docker compose -f compose.always-on-demo.yaml up -d mysql
docker compose -f compose.always-on-demo.yaml --profile bootstrap run --rm live-db-bootstrap
docker compose -f compose.always-on-demo.yaml --profile bootstrap run --rm live-migrate
docker compose -f compose.always-on-demo.yaml --profile bootstrap run --rm replay-migrate
docker compose -f compose.always-on-demo.yaml up -d crossref-proxy
docker compose -f compose.always-on-demo.yaml up -d jenkins-agent
docker compose -f compose.always-on-demo.yaml up -d jenkins-controller
docker compose -f compose.always-on-demo.yaml up -d gateway

for _ in {1..60}; do
  if curl --fail --silent http://127.0.0.1:9092/healthz >/dev/null; then
    for volume in open-metadata-sync-public-demo-mysql-data open-metadata-sync-public-demo-jenkins-home; do
      docker volume inspect "$volume" > "build/demo/$volume-after.json"
      if [[ "$(cat "build/demo/$volume-before.sha256")" != "not-created-yet" ]]; then
        shasum -a 256 -c "build/demo/$volume-before.sha256"
        cmp "build/demo/$volume-before.json" "build/demo/$volume-after.json"
      fi
    done
    echo "Dedicated demo gateway is ready on http://127.0.0.1:9092"
    exit 0
  fi
  sleep 2
done
echo "Dedicated demo gateway did not become ready" >&2
exit 1
