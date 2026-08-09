#!/usr/bin/env bash
set -euo pipefail

: "${DEMO_MYSQL_PASSWORD:?DEMO_MYSQL_PASSWORD is required}"

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"

PID_FILE=build/demo/cloudflared.pid
if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  kill "$(cat "$PID_FILE")"
  wait "$(cat "$PID_FILE")" 2>/dev/null || true
fi
rm -f "$PID_FILE" build/demo/tunnel-url.txt

timestamp=$(date +%Y%m%d-%H%M%S)
evidence_dir="${DEMO_EVIDENCE_DIR:-/Volumes/sd-128/open-metadata-sync/demo/$timestamp}"
mkdir -p "$evidence_dir"
export MYSQL_PWD="$DEMO_MYSQL_PASSWORD"
docker exec -e MYSQL_PWD open-metadata-sync-demo-mysql \
  mysqldump -uopen_metadata --single-transaction --databases \
    open_metadata open_metadata_benchmark_preflight | gzip > "$evidence_dir/demo-mysql.sql.gz"
if compgen -G 'build/jenkins/*' >/dev/null || compgen -G 'benchmark-evidence/benchmark-10000-*' >/dev/null; then
  tar -czf "$evidence_dir/demo-jenkins-evidence.tar.gz" \
    build/jenkins benchmark-evidence/benchmark-10000-* 2>/dev/null || true
fi
docker compose -f compose.demo.yaml stop mysql
printf 'Demo stopped; volume preserved; evidence=%s\n' "$evidence_dir"
