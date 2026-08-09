#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"

expected_revision=47461be71ae4add166b5a5ea157465c370894330
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
chmod 600 .demo-secrets/*

docker compose -f compose.always-on-demo.yaml build jenkins-agent jenkins-controller gateway
docker compose -f compose.always-on-demo.yaml up -d mysql
docker compose -f compose.always-on-demo.yaml --profile setup run --rm migrate
docker compose -f compose.always-on-demo.yaml up -d jenkins-agent jenkins-controller gateway

for _ in {1..60}; do
  if curl --fail --silent http://127.0.0.1:9092/healthz >/dev/null; then
    echo "Dedicated demo gateway is ready on http://127.0.0.1:9092"
    exit 0
  fi
  sleep 2
done
echo "Dedicated demo gateway did not become ready" >&2
exit 1
