#!/usr/bin/env bash
set -euo pipefail

: "${DB_HOST:?DB_HOST is required}"
: "${DB_PORT:?DB_PORT is required}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"
source scripts/demo-mysql-client.sh

demo_validate_database_boundary
demo_verify_database_sentinel open_metadata_benchmark_preflight
ready=$(demo_mysql_query open_metadata_benchmark_preflight \
  "SELECT CONCAT((SELECT COUNT(*) FROM work), '|', (SELECT COUNT(*) FROM sync_execution WHERE mode = 'BENCHMARK' AND business_status = 'COMPLETED'));" )
IFS='|' read -r work_count completed_count <<< "$ready"
if [[ "$work_count" != "10000" || "$completed_count" -lt "1" ]]; then
  echo "NO_OP requires a completed 10K INITIAL demo" >&2
  exit 1
fi
echo "10K NO_OP database precondition verified"
