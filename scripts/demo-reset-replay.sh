#!/usr/bin/env bash
set -euo pipefail

: "${REQUEST_ID:?REQUEST_ID is required}"
: "${SOURCE_EXECUTION_ID:?SOURCE_EXECUTION_ID is required}"
: "${DB_HOST:?DB_HOST is required}"
: "${DB_PORT:?DB_PORT is required}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"
: "${DEMO_REPLAY_RESET_ACK:?DEMO_REPLAY_RESET_ACK is required}"

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"
source scripts/demo-mysql-client.sh

if [[ ! "$REQUEST_ID" =~ ^[A-Za-z0-9._:-]+$ ]]; then
  echo "Invalid REQUEST_ID" >&2
  exit 1
fi
if [[ ! "$SOURCE_EXECUTION_ID" =~ ^[0-9a-fA-F-]{36}$ ]]; then
  echo "Invalid SOURCE_EXECUTION_ID" >&2
  exit 1
fi
if [[ "$DEMO_REPLAY_RESET_ACK" != "REPLAY_ERRORS" ]]; then
  echo "Replay demo reset requires explicit acknowledgement" >&2
  exit 1
fi
if [[ "$SOURCE_EXECUTION_ID" != "00000000-0000-0000-0000-00000000d001" ]]; then
  echo "Replay demo reset requires the fixed demo source execution" >&2
  exit 1
fi

demo_validate_database_boundary
demo_verify_database_sentinel open_metadata

OUTPUT_DIR="${DEMO_OUTPUT_DIR:-build/jenkins}"
mkdir -p "$OUTPUT_DIR"
demo_mysql_stdin open_metadata < scripts/demo-replay-fixture.sql

query=$(cat <<SQL
SELECT source.business_status,
       source_error.status,
       source_error.error_type,
       source_error.error_code,
       source_error.message,
       source_staging.doi,
       source_error.replay_count,
       (SELECT COUNT(*) FROM work WHERE doi = '10.5555/demo-replay')
FROM sync_execution source
JOIN sync_error source_error ON source_error.execution_id = source.id
JOIN staging_work source_staging
  ON source_staging.execution_id = source_error.execution_id
 AND source_staging.staging_key = source_error.staging_key
WHERE source.id = UUID_TO_BIN('${SOURCE_EXECUTION_ID}')
  AND source_error.error_code = 'DEMO_TRANSIENT_WRITE'
LIMIT 1;
SQL
)

result=$(demo_mysql_query open_metadata "$query")
IFS=$'\t' read -r source_status error_status error_type error_code error_message doi replay_count target_count <<< "$result"

if [[ "$source_status" != "FAILED" || "$error_status" != "OPEN" \
   || "$error_type" != "PERSISTENCE" || "$error_code" != "DEMO_TRANSIENT_WRITE" \
   || "$error_message" != "Simulated transient write failure before target insert" \
   || "$doi" != "10.5555/demo-replay" || "$replay_count" != "0" || "$target_count" != "0" ]]; then
  echo "Replay demo reset verification failed: $result" >&2
  exit 1
fi

json="$OUTPUT_DIR/replay-before-${REQUEST_ID}.json"
markdown="$OUTPUT_DIR/replay-before-${REQUEST_ID}.md"
printf 'BEFORE: source=%s error=%s code=%s doi=%s replay_count=%s target_rows=%s\n' \
  "$source_status" "$error_status" "$error_code" "$doi" "$replay_count" "$target_count"
printf '{\n  "request_id": "%s",\n  "source_execution_id": "%s",\n  "source_status": "%s",\n  "error_status": "%s",\n  "error_type": "%s",\n  "error_code": "%s",\n  "error_message": "%s",\n  "doi": "%s",\n  "replay_count": %s,\n  "target_count": %s\n}\n' \
  "$REQUEST_ID" "$SOURCE_EXECUTION_ID" "$source_status" "$error_status" "$error_type" \
  "$error_code" "$error_message" "$doi" "$replay_count" "$target_count" > "$json"
printf '# Replay Errors Demo: Before\n\n| Evidence | Value |\n|---|---:|\n| Source status | %s |\n| Error status | %s |\n| Error type | %s |\n| Error code | %s |\n| Error message | %s |\n| DOI | %s |\n| Replay count | %s |\n| Target rows | %s |\n' \
  "$source_status" "$error_status" "$error_type" "$error_code" "$error_message" "$doi" \
  "$replay_count" "$target_count" > "$markdown"
