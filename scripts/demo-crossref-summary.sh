#!/usr/bin/env bash
set -euo pipefail

: "${REQUEST_ID:?REQUEST_ID is required}"
: "${MODE:?MODE is required}"
: "${BUILD_RESULT:?BUILD_RESULT is required}"
: "${SUMMARY_REASON:?SUMMARY_REASON is required}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"
[[ "$REQUEST_ID" =~ ^public-[0-9]+-[A-Za-z0-9_-]{8,32}$ ]] || { echo "Invalid public REQUEST_ID" >&2; exit 1; }
[[ "$MODE" == "BACKFILL" || "$MODE" == "REPLAY_ERRORS" ]] || { echo "Invalid public MODE" >&2; exit 1; }
[[ "$BUILD_RESULT" =~ ^(SUCCESS|UNSTABLE|NOT_BUILT|FAILURE)$ ]] || { echo "Invalid build result" >&2; exit 1; }
[[ "$SUMMARY_REASON" =~ ^[A-Z_]+$ ]] || { echo "Invalid summary reason" >&2; exit 1; }

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"
source scripts/demo-mysql-client.sh
demo_validate_database_boundary
demo_verify_database_sentinel open_metadata_live_demo \
  00000000-0000-0000-0000-00000000d100 \
  'open_metadata_live_demo@%' \
  open-metadata-sync-public-live-demo

preflight="${DEMO_OUTPUT_DIR:-build/jenkins}/demo-preflight-${REQUEST_ID}.properties"
[[ -f "$preflight" ]] || { echo "Preflight evidence is missing" >&2; exit 1; }
property() { sed -n "s/^$1=//p" "$preflight"; }
source_execution_id=$(property source_execution_id)
selected_error_upper_bound=$(property selected_error_upper_bound)
selected_error_count=$(property selected_error_count)

read -r total_open_errors replayable_open_errors <<< "$(demo_mysql_query open_metadata_live_demo "
SELECT (SELECT COUNT(*) FROM sync_error error WHERE error.status = 'OPEN'),
       (SELECT COUNT(*) FROM sync_error error
          JOIN staging_work staging
            ON staging.execution_id = error.execution_id AND staging.staging_key = error.staging_key
          JOIN sync_execution source
            ON source.id = error.execution_id
         WHERE error.status = 'OPEN'
           AND source.mode = 'BACKFILL'
           AND source.business_status = 'COMPLETED_WITH_ERRORS'
           AND source.finished_at IS NOT NULL);")"

error_groups=$(demo_mysql_query open_metadata_live_demo "
SELECT CONCAT('[', COALESCE(GROUP_CONCAT(JSON_OBJECT(
              'type', grouped.safe_type, 'code', grouped.safe_code, 'count', grouped.error_count)
              ORDER BY grouped.safe_type, grouped.safe_code SEPARATOR ','), ''), ']')
  FROM (
    SELECT CASE WHEN error.error_type IN ('VALIDATION', 'CONFLICT') THEN error.error_type ELSE 'OTHER' END safe_type,
           CASE WHEN error.error_code = 'SAME_TIMESTAMP_DIFFERENT_CONTENT' THEN error.error_code ELSE 'OTHER' END safe_code,
           COUNT(*) error_count
      FROM sync_error error
     WHERE error.status = 'OPEN'
     GROUP BY safe_type, safe_code
  ) grouped;")
[[ -n "$error_groups" ]] || error_groups='[]'
error_group_text=$(demo_mysql_query open_metadata_live_demo "
SELECT COALESCE(GROUP_CONCAT(CONCAT(grouped.safe_type, '/', grouped.safe_code, '=', grouped.error_count)
                            ORDER BY grouped.safe_type, grouped.safe_code SEPARATOR ', '), 'none')
  FROM (
    SELECT CASE WHEN error.error_type IN ('VALIDATION', 'CONFLICT') THEN error.error_type ELSE 'OTHER' END safe_type,
           CASE WHEN error.error_code = 'SAME_TIMESTAMP_DIFFERENT_CONTENT' THEN error.error_code ELSE 'OTHER' END safe_code,
           COUNT(*) error_count
      FROM sync_error error
     WHERE error.status = 'OPEN'
     GROUP BY safe_type, safe_code
  ) grouped;")

execution_id=
business_status=
expected_count=0
staging_count=0
accounted_count=0
pages_fetched=0
if execution_row=$(demo_mysql_query open_metadata_live_demo "
SELECT BIN_TO_UUID(execution.id), execution.business_status,
       COALESCE(execution.expected_count, 0),
       (SELECT COUNT(*) FROM staging_work staging WHERE staging.execution_id = execution.id),
       (SELECT COALESCE(SUM(result.inserted_count + result.superseded_count + result.no_op_count + result.conflict_count + result.index_advanced_count + result.updated_count + result.validation_error_count), 0)
          FROM sync_chunk_result result WHERE result.execution_id = execution.id),
       COALESCE(execution.collection_pages_fetched, 0)
  FROM sync_execution execution
 WHERE execution.request_id = '${REQUEST_ID}'
 LIMIT 1;"); then
  if [[ -n "$execution_row" ]]; then
    IFS=$'\t' read -r execution_id business_status expected_count staging_count accounted_count pages_fetched <<< "$execution_row"
    [[ "$execution_id" =~ ^[0-9A-Fa-f-]{36}$ ]] || { echo "Invalid summary execution ID" >&2; exit 1; }
    [[ "$business_status" =~ ^[A-Z_]+$ ]] || business_status=OTHER
  fi
fi

read -r collect_step_duration_ms sync_step_duration_ms <<< "$(demo_mysql_query open_metadata_live_demo "
SELECT COALESCE(CAST(MAX(CASE WHEN step.STEP_NAME = 'collect'
                              AND step.START_TIME IS NOT NULL AND step.END_TIME IS NOT NULL
                         THEN TIMESTAMPDIFF(MICROSECOND, step.START_TIME, step.END_TIME) DIV 1000 END) AS CHAR), 'null'),
       COALESCE(CAST(MAX(CASE WHEN step.STEP_NAME = 'sync'
                              AND step.START_TIME IS NOT NULL AND step.END_TIME IS NOT NULL
                         THEN TIMESTAMPDIFF(MICROSECOND, step.START_TIME, step.END_TIME) DIV 1000 END) AS CHAR), 'null')
  FROM sync_execution execution
  LEFT JOIN BATCH_STEP_EXECUTION step
    ON step.JOB_EXECUTION_ID = execution.batch_job_execution_id
 WHERE execution.request_id = '${REQUEST_ID}';")"
for duration in "$collect_step_duration_ms" "$sync_step_duration_ms"; do
  [[ "$duration" == "null" || "$duration" =~ ^[0-9]+$ ]] || {
    echo "Invalid Batch step duration" >&2
    exit 1
  }
done

source_remaining_open_errors=0
source_resolved_errors=0
if [[ -n "$source_execution_id" && "$selected_error_upper_bound" =~ ^[1-9][0-9]*$ ]]; then
  read -r source_remaining_open_errors source_resolved_errors <<< "$(demo_mysql_query open_metadata_live_demo "
SELECT SUM(CASE WHEN error.status = 'OPEN' THEN 1 ELSE 0 END),
       SUM(CASE WHEN error.status = 'RESOLVED' THEN 1 ELSE 0 END)
  FROM sync_error error
 WHERE error.execution_id = UUID_TO_BIN('${source_execution_id}')
   AND error.error_key <= ${selected_error_upper_bound};")"
fi

next_mode=BACKFILL
if [[ "$replayable_open_errors" != "0" ]]; then
  next_mode=REPLAY_ERRORS
elif [[ "$total_open_errors" != "0" ]]; then
  next_mode=OPERATOR_REVIEW
fi

output_dir=${DEMO_OUTPUT_DIR:-build/jenkins}
mkdir -p "$output_dir"
json="$output_dir/crossref-${REQUEST_ID}.json"
html="$output_dir/crossref-${REQUEST_ID}.html"
properties="$output_dir/crossref-${REQUEST_ID}.properties"
execution_json=null
source_id_json=null
[[ -z "$execution_id" ]] || execution_json="\"$execution_id\""
[[ -z "$source_execution_id" ]] || source_id_json="\"$source_execution_id\""
printf '{"schema_version":1,"request_id":"%s","mode":"%s","build_result":"%s","reason":"%s","collect_step_duration_ms":%s,"sync_step_duration_ms":%s,"sync_execution_id":%s,"source_execution_id":%s,"selected_error_upper_bound":%s,"selected_error_count":%s,"source_remaining_open_errors":%s,"source_resolved_errors":%s,"total_open_errors":%s,"replayable_open_errors":%s,"error_groups":%s,"next_mode":"%s","expected_count":%s,"staging_count":%s,"accounted_count":%s,"pages_fetched":%s,"business_status":"%s"}\n' \
  "$REQUEST_ID" "$MODE" "$BUILD_RESULT" "$SUMMARY_REASON" \
  "$collect_step_duration_ms" "$sync_step_duration_ms" "$execution_json" "$source_id_json" \
  "$selected_error_upper_bound" "$selected_error_count" "$source_remaining_open_errors" "$source_resolved_errors" \
  "$total_open_errors" "$replayable_open_errors" \
  "$error_groups" "$next_mode" "$expected_count" "$staging_count" "$accounted_count" "$pages_fetched" "$business_status" > "$json"

html_escape() {
  local value=$1
  value=${value//&/\&amp;}
  value=${value//</\&lt;}
  value=${value//>/\&gt;}
  value=${value//\"/\&quot;}
  value=${value//\'/\&#39;}
  printf '%s' "$value"
}
duration_text() {
  local milliseconds=$1
  if [[ "$milliseconds" == "null" ]]; then
    printf 'Not run'
  else
    printf '%d.%03d s (%d ms)' \
      "$((milliseconds / 1000))" "$((milliseconds % 1000))" "$milliseconds"
  fi
}
html_row() {
  printf '<tr><th scope="row">%s</th><td>%s</td></tr>\n' \
    "$(html_escape "$1")" "$(html_escape "$2")"
}
{
  printf '%s\n' '<!doctype html>' '<html lang="en">' '<head>' '<meta charset="utf-8">' \
    '<meta name="viewport" content="width=device-width,initial-scale=1">' \
    '<title>Crossref demo result</title>' \
    '<style>body{margin:0;background:#f5f4ef;color:#1d1d1b;font:16px/1.5 system-ui,sans-serif}main{max-width:58rem;margin:auto;padding:2rem}h1,h2{line-height:1.2}section{margin-top:2rem}table{width:100%;border-collapse:collapse;background:#fff}th,td{padding:.65rem;text-align:left;border:1px solid #ccc}th{width:42%;background:#eceae2}code{font-family:ui-monospace,monospace}</style>' \
    '</head>' '<body>' '<main>' '<header><h1>Crossref demo result</h1><p>Human-readable view of the archived JSON source of truth.</p></header>' \
    '<section aria-labelledby="result-heading"><h2 id="result-heading">Result</h2><table><tbody>'
  html_row 'Request ID' "$REQUEST_ID"
  html_row 'Mode' "$MODE"
  html_row 'Build result' "$BUILD_RESULT"
  html_row 'Reason' "$SUMMARY_REASON"
  html_row 'Business status' "${business_status:--}"
  html_row 'Next mode' "$next_mode"
  printf '%s\n' '</tbody></table></section>' \
    '<section aria-labelledby="timing-heading"><h2 id="timing-heading">Step timing</h2><table><tbody>'
  html_row 'Collect step' "$(duration_text "$collect_step_duration_ms")"
  html_row 'Sync step' "$(duration_text "$sync_step_duration_ms")"
  printf '%s\n' '</tbody></table></section>' \
    '<section aria-labelledby="evidence-heading"><h2 id="evidence-heading">Reconciliation</h2><table><tbody>'
  html_row 'Expected / staging / accounted' "$expected_count / $staging_count / $accounted_count"
  html_row 'Pages fetched' "$pages_fetched"
  html_row 'OPEN / replayable OPEN' "$total_open_errors / $replayable_open_errors"
  html_row 'Error groups' "$error_group_text"
  html_row 'Selected error upper bound / count' "$selected_error_upper_bound / $selected_error_count"
  html_row 'Source remaining / resolved' "$source_remaining_open_errors / $source_resolved_errors"
  printf '%s\n' '</tbody></table></section>' \
    '<section aria-labelledby="identity-heading"><h2 id="identity-heading">Execution identity</h2><table><tbody>'
  html_row 'Sync execution' "${execution_id:--}"
  html_row 'Source execution' "${source_execution_id:--}"
  html_row 'Machine-readable artifact' "crossref-${REQUEST_ID}.json"
  printf '%s\n' '</tbody></table></section>' '</main>' '</body>' '</html>'
} > "$html"
printf 'schema_version=1\nrequest_id=%s\nmode=%s\nbuild_result=%s\nreason=%s\nsync_execution_id=%s\nsource_execution_id=%s\nselected_error_upper_bound=%s\nselected_error_count=%s\nsource_remaining_open_errors=%s\nsource_resolved_errors=%s\ntotal_open_errors=%s\nreplayable_open_errors=%s\nnext_mode=%s\n' \
  "$REQUEST_ID" "$MODE" "$BUILD_RESULT" "$SUMMARY_REASON" "$execution_id" "$source_execution_id" \
  "$selected_error_upper_bound" "$selected_error_count" "$source_remaining_open_errors" "$source_resolved_errors" \
  "$total_open_errors" "$replayable_open_errors" "$next_mode" > "$properties"
printf 'DEMO_RESULT request_id=%s mode=%s result=%s open=%s replayable=%s source=%s next=%s\n' \
  "$REQUEST_ID" "$MODE" "$BUILD_RESULT" "$total_open_errors" "$replayable_open_errors" "${source_execution_id:--}" "$next_mode"

if [[ "$MODE" == "BACKFILL" && "$BUILD_RESULT" == "SUCCESS" ]] \
    && [[ "$business_status" != "COMPLETED" || "$expected_count" != "10000" \
          || "$staging_count" != "10000" || "$accounted_count" != "10000" \
          || "$pages_fetched" != "10" || "$total_open_errors" != "0" ]]; then
  echo "BACKFILL SUCCESS evidence is inconsistent" >&2
  exit 1
fi
if [[ "$MODE" == "REPLAY_ERRORS" && "$BUILD_RESULT" == "SUCCESS" ]] \
    && [[ -z "$source_execution_id" || "$business_status" != "COMPLETED" \
          || "$source_remaining_open_errors" != "0" \
          || "$source_resolved_errors" -lt "$selected_error_count" ]]; then
  echo "REPLAY_ERRORS SUCCESS evidence is inconsistent" >&2
  exit 1
fi
