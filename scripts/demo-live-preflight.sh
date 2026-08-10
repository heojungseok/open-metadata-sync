#!/usr/bin/env bash
set -euo pipefail

: "${REQUEST_ID:?REQUEST_ID is required}"
: "${MODE:?MODE is required}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"
[[ "$REQUEST_ID" =~ ^public-[0-9]+-[A-Za-z0-9_-]{8,32}$ ]] || { echo "Invalid public REQUEST_ID" >&2; exit 1; }
[[ "$MODE" == "BACKFILL" || "$MODE" == "REPLAY_ERRORS" ]] || { echo "Invalid public MODE" >&2; exit 1; }
[[ "$DB_USERNAME" == "open_metadata_live_demo" ]] || { echo "Live preflight requires the live-only account" >&2; exit 1; }

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"
source scripts/demo-mysql-client.sh
demo_validate_database_boundary
demo_verify_database_sentinel open_metadata_live_demo \
  00000000-0000-0000-0000-00000000d100 \
  'open_metadata_live_demo@%' \
  open-metadata-sync-public-live-demo

read -r total_open_errors replayable_open_errors <<< "$(demo_mysql_query open_metadata_live_demo "
SELECT (SELECT COUNT(*) FROM sync_error error WHERE error.status = 'OPEN'),
       (SELECT COUNT(*)
          FROM sync_error error
          JOIN staging_work staging
            ON staging.execution_id = error.execution_id
           AND staging.staging_key = error.staging_key
          JOIN sync_execution execution
            ON execution.id = error.execution_id
         WHERE error.status = 'OPEN'
           AND execution.mode = 'BACKFILL'
           AND execution.business_status = 'COMPLETED_WITH_ERRORS'
           AND execution.finished_at IS NOT NULL);")"

source_row=$(demo_mysql_query open_metadata_live_demo "
SELECT BIN_TO_UUID(execution.id),
       MAX(error.error_key),
       COUNT(*)
  FROM sync_execution execution
  JOIN sync_error error
    ON error.execution_id = execution.id
   AND error.status = 'OPEN'
  JOIN staging_work staging
    ON staging.execution_id = error.execution_id
   AND staging.staging_key = error.staging_key
 WHERE execution.mode = 'BACKFILL'
   AND execution.business_status = 'COMPLETED_WITH_ERRORS'
   AND execution.finished_at IS NOT NULL
 GROUP BY execution.id, execution.started_at
 ORDER BY execution.started_at DESC, BIN_TO_UUID(execution.id) DESC
 LIMIT 1;")

source_execution_id=
selected_error_upper_bound=0
selected_error_count=0
if [[ -n "$source_row" ]]; then
  IFS=$'\t' read -r source_execution_id selected_error_upper_bound selected_error_count <<< "$source_row"
  [[ "$source_execution_id" =~ ^[0-9A-Fa-f-]{36}$ ]] || { echo "Invalid selected source execution" >&2; exit 1; }
  [[ "$selected_error_upper_bound" =~ ^[1-9][0-9]*$ ]] || { echo "Invalid selected error upper bound" >&2; exit 1; }
  [[ "$selected_error_count" =~ ^[1-9][0-9]*$ ]] || { echo "Invalid selected error count" >&2; exit 1; }
fi

decision=RUN
next_mode="$MODE"
if [[ "$MODE" == "BACKFILL" && "$total_open_errors" != "0" ]]; then
  decision=OPEN_ERRORS_REQUIRE_REPLAY
  if [[ "$replayable_open_errors" == "0" || -z "$source_execution_id" ]]; then
    decision=OPERATOR_REVIEW
    next_mode=OPERATOR_REVIEW
  else
    next_mode=REPLAY_ERRORS
  fi
elif [[ "$MODE" == "REPLAY_ERRORS" && -z "$source_execution_id" ]]; then
  if [[ "$total_open_errors" == "0" ]]; then
    decision=NO_REPLAY_TARGET
    next_mode=BACKFILL
  else
    decision=OPERATOR_REVIEW
    next_mode=OPERATOR_REVIEW
  fi
fi

output_dir=${DEMO_OUTPUT_DIR:-build/jenkins}
mkdir -p "$output_dir"
output="$output_dir/demo-preflight-${REQUEST_ID}.properties"
temporary="$output.tmp"
printf 'request_id=%s\nmode=%s\ndecision=%s\ntotal_open_errors=%s\nreplayable_open_errors=%s\nsource_execution_id=%s\nselected_error_upper_bound=%s\nselected_error_count=%s\nnext_mode=%s\n' \
  "$REQUEST_ID" "$MODE" "$decision" "$total_open_errors" "$replayable_open_errors" \
  "$source_execution_id" "$selected_error_upper_bound" "$selected_error_count" "$next_mode" > "$temporary"
mv "$temporary" "$output"
[[ "$decision" == "RUN" ]] || exit 3
