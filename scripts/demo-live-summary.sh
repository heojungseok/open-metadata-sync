#!/usr/bin/env bash
set -euo pipefail

: "${REQUEST_ID:?REQUEST_ID is required}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"
[[ "$REQUEST_ID" =~ ^public-[0-9]+-[A-Za-z0-9_-]{8,32}$ ]] || {
  echo "Invalid public REQUEST_ID" >&2
  exit 1
}
[[ "$DB_USERNAME" == "open_metadata_live_demo" ]] || {
  echo "Live summary requires the live-only database account" >&2
  exit 1
}

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"
source scripts/demo-mysql-client.sh
demo_validate_database_boundary
demo_verify_database_sentinel open_metadata_live_demo \
  00000000-0000-0000-0000-00000000d100 \
  'open_metadata_live_demo@%' \
  open-metadata-sync-public-live-demo

result=$(demo_mysql_query open_metadata_live_demo "
SELECT execution.mode,
       execution.created_from,
       execution.created_until,
       execution.max_items,
       execution.expected_count,
       (SELECT COUNT(*) FROM staging_work WHERE execution_id = execution.id),
       (SELECT COALESCE(SUM(inserted_count + superseded_count + no_op_count + conflict_count + index_advanced_count + updated_count + validation_error_count), 0) FROM sync_chunk_result WHERE execution_id = execution.id),
       (SELECT COUNT(*) FROM sync_error WHERE execution_id = execution.id AND status = 'OPEN'),
       execution.business_status
FROM sync_execution execution
WHERE execution.request_id = '${REQUEST_ID}'
LIMIT 1;")
IFS=$'\t' read -r mode created_from created_until max_items expected staging accounted open_errors status <<< "$result"

if [[ "$mode" != "BACKFILL" || "$created_from" != "2026-08-01" \
   || "$created_until" != "2026-08-08" || "$max_items" != "10000" \
   || "$expected" != "10000" || "$staging" != "10000" || "$accounted" != "10000" \
   || "$open_errors" != "0" || "$status" != "COMPLETED" ]]; then
  echo "Live Crossref verification failed: $result" >&2
  exit 1
fi

OUTPUT_DIR="${DEMO_OUTPUT_DIR:-build/jenkins}"
mkdir -p "$OUTPUT_DIR"
json="$OUTPUT_DIR/live-crossref-${REQUEST_ID}.json"
markdown="$OUTPUT_DIR/live-crossref-${REQUEST_ID}.md"
printf '{\n  "request_id": "%s",\n  "mode": "%s",\n  "created_from": "%s",\n  "created_until": "%s",\n  "max_items": %s,\n  "expected_count": %s,\n  "staging_count": %s,\n  "accounted_count": %s,\n  "open_errors": %s,\n  "status": "%s"\n}\n' \
  "$REQUEST_ID" "$mode" "$created_from" "$created_until" "$max_items" "$expected" \
  "$staging" "$accounted" "$open_errors" "$status" > "$json"
printf '# Live Crossref 10K\n\n| Evidence | Value |\n|---|---:|\n| Request ID | %s |\n| Range | %s through %s |\n| Expected | %s |\n| Staging | %s |\n| Accounted | %s |\n| Open errors | %s |\n| Status | %s |\n' \
  "$REQUEST_ID" "$created_from" "$created_until" "$expected" "$staging" "$accounted" \
  "$open_errors" "$status" > "$markdown"
echo "Live Crossref 10K verification passed"
