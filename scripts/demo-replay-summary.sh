#!/usr/bin/env bash
set -euo pipefail

: "${REQUEST_ID:?REQUEST_ID is required}"
: "${SOURCE_EXECUTION_ID:?SOURCE_EXECUTION_ID is required}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"

if [[ ! "$REQUEST_ID" =~ ^[A-Za-z0-9._:-]+$ ]]; then
  echo "Invalid REQUEST_ID" >&2
  exit 1
fi
if [[ ! "$SOURCE_EXECUTION_ID" =~ ^[0-9a-fA-F-]{36}$ ]]; then
  echo "Invalid SOURCE_EXECUTION_ID" >&2
  exit 1
fi

DEMO_DB_CONTAINER="${DEMO_DB_CONTAINER:-open-metadata-sync-demo-mysql}"
OUTPUT_DIR="${DEMO_OUTPUT_DIR:-build/jenkins}"
mkdir -p "$OUTPUT_DIR"
export MYSQL_PWD="$DB_PASSWORD"

query=$(cat <<SQL
SELECT source_error.status,
       source_error.replay_count,
       (SELECT COUNT(*) FROM sync_error WHERE execution_id = replay.id),
       (SELECT COUNT(*) FROM staging_work WHERE execution_id = replay.id),
       (SELECT COALESCE(SUM(no_op_count), 0) FROM sync_chunk_result WHERE execution_id = replay.id),
       (SELECT COUNT(*) FROM work),
       replay.business_status
FROM sync_execution replay
JOIN sync_error source_error
  ON source_error.execution_id = UUID_TO_BIN('${SOURCE_EXECUTION_ID}')
 AND source_error.error_code = 'DEMO_FIXED'
WHERE replay.request_id = '${REQUEST_ID}'
  AND replay.mode = 'REPLAY_ERRORS'
LIMIT 1;
SQL
)

result=$(docker exec -e MYSQL_PWD "$DEMO_DB_CONTAINER" \
  mysql --batch --skip-column-names -u"$DB_USERNAME" open_metadata -e "$query")
IFS=$'\t' read -r error_status replay_count new_error_count replay_staging_count no_op_count target_count replay_status <<< "$result"

if [[ "$error_status" != "RESOLVED" || "$replay_count" != "1" || "$new_error_count" != "0" \
   || "$replay_staging_count" != "1" || "$no_op_count" != "1" || "$target_count" != "1" \
   || "$replay_status" != "COMPLETED" ]]; then
  echo "Replay demo verification failed: $result" >&2
  exit 1
fi

json="$OUTPUT_DIR/replay-${REQUEST_ID}.json"
markdown="$OUTPUT_DIR/replay-${REQUEST_ID}.md"
printf '{\n  "request_id": "%s",\n  "source_execution_id": "%s",\n  "error_status": "%s",\n  "replay_count": %s,\n  "new_error_count": %s,\n  "replay_staging_count": %s,\n  "no_op_count": %s,\n  "target_count": %s,\n  "replay_status": "%s"\n}\n' \
  "$REQUEST_ID" "$SOURCE_EXECUTION_ID" "$error_status" "$replay_count" "$new_error_count" \
  "$replay_staging_count" "$no_op_count" "$target_count" "$replay_status" > "$json"
printf '# Replay Errors Demo\n\n| Evidence | Value |\n|---|---:|\n| Source error | %s |\n| Replay count | %s |\n| New errors | %s |\n| Replay staging | %s |\n| No-op outcomes | %s |\n| Target rows | %s |\n| Replay status | %s |\n' \
  "$error_status" "$replay_count" "$new_error_count" "$replay_staging_count" "$no_op_count" \
  "$target_count" "$replay_status" > "$markdown"
