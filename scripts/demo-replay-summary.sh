#!/usr/bin/env bash
set -euo pipefail

: "${REQUEST_ID:?REQUEST_ID is required}"
: "${SOURCE_EXECUTION_ID:?SOURCE_EXECUTION_ID is required}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"

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

demo_validate_database_boundary
demo_verify_database_sentinel open_metadata
OUTPUT_DIR="${DEMO_OUTPUT_DIR:-build/jenkins}"
mkdir -p "$OUTPUT_DIR"

query=$(cat <<SQL
SELECT source_error.status,
       source_error.replay_count,
       (SELECT COUNT(*) FROM sync_error WHERE execution_id = replay.id),
       (SELECT COUNT(*) FROM staging_work WHERE execution_id = replay.id),
       (SELECT COALESCE(SUM(inserted_count), 0) FROM sync_chunk_result WHERE execution_id = replay.id),
       (SELECT COUNT(*) FROM work WHERE doi = '10.5555/demo-replay'),
       replay.business_status
FROM sync_execution replay
JOIN sync_error source_error
  ON source_error.execution_id = UUID_TO_BIN('${SOURCE_EXECUTION_ID}')
 AND source_error.error_code = 'DEMO_TRANSIENT_WRITE'
WHERE replay.request_id = '${REQUEST_ID}'
  AND replay.mode = 'REPLAY_ERRORS'
LIMIT 1;
SQL
)

result=$(demo_mysql_query open_metadata "$query")
IFS=$'\t' read -r error_status replay_count new_error_count replay_staging_count inserted_count target_count replay_status <<< "$result"

if [[ "$error_status" != "RESOLVED" || "$replay_count" != "1" || "$new_error_count" != "0" \
   || "$replay_staging_count" != "1" || "$inserted_count" != "1" || "$target_count" != "1" \
   || "$replay_status" != "COMPLETED" ]]; then
  echo "Replay demo verification failed: $result" >&2
  exit 1
fi

json="$OUTPUT_DIR/replay-after-${REQUEST_ID}.json"
markdown="$OUTPUT_DIR/replay-after-${REQUEST_ID}.md"
printf 'AFTER: replay=%s error=%s doi=10.5555/demo-replay replay_count=%s inserted=%s target_rows=%s\n' \
  "$replay_status" "$error_status" "$replay_count" "$inserted_count" "$target_count"
printf '{\n  "request_id": "%s",\n  "source_execution_id": "%s",\n  "error_status": "%s",\n  "replay_count": %s,\n  "new_error_count": %s,\n  "replay_staging_count": %s,\n  "inserted_count": %s,\n  "target_count": %s,\n  "replay_status": "%s"\n}\n' \
  "$REQUEST_ID" "$SOURCE_EXECUTION_ID" "$error_status" "$replay_count" "$new_error_count" \
  "$replay_staging_count" "$inserted_count" "$target_count" "$replay_status" > "$json"
printf '# Replay Errors Demo: After\n\n| Evidence | Value |\n|---|---:|\n| Source error | %s |\n| Replay count | %s |\n| New errors | %s |\n| Replay staging | %s |\n| Inserted outcomes | %s |\n| Target rows | %s |\n| Replay status | %s |\n' \
  "$error_status" "$replay_count" "$new_error_count" "$replay_staging_count" "$inserted_count" \
  "$target_count" "$replay_status" > "$markdown"
