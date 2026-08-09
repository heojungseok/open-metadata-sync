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
SELECT BIN_TO_UUID(execution.id),
       execution.batch_job_execution_id,
       execution.mode,
       execution.created_from,
       execution.created_until,
       execution.max_items,
       execution.expected_count,
       execution.collection_pages_fetched,
       execution.collection_reported_total,
       execution.collection_stop_reason,
       execution.collection_page_safety_cap,
       (SELECT COUNT(*) FROM staging_work WHERE execution_id = execution.id),
       (SELECT COUNT(DISTINCT doi) FROM staging_work WHERE execution_id = execution.id),
       (SELECT COALESCE(SUM(inserted_count), 0) FROM sync_chunk_result WHERE execution_id = execution.id),
       (SELECT COALESCE(SUM(superseded_count), 0) FROM sync_chunk_result WHERE execution_id = execution.id),
       (SELECT COALESCE(SUM(no_op_count), 0) FROM sync_chunk_result WHERE execution_id = execution.id),
       (SELECT COALESCE(SUM(conflict_count), 0) FROM sync_chunk_result WHERE execution_id = execution.id),
       (SELECT COALESCE(SUM(index_advanced_count), 0) FROM sync_chunk_result WHERE execution_id = execution.id),
       (SELECT COALESCE(SUM(updated_count), 0) FROM sync_chunk_result WHERE execution_id = execution.id),
       (SELECT COALESCE(SUM(validation_error_count), 0) FROM sync_chunk_result WHERE execution_id = execution.id),
       (SELECT COUNT(DISTINCT target.doi) FROM work target JOIN staging_work staging ON staging.doi = target.doi WHERE staging.execution_id = execution.id),
       (SELECT COUNT(*)
          FROM staging_work staging
          JOIN work target ON target.doi = staging.doi
         WHERE staging.execution_id = execution.id
           AND staging.indexed_at = (SELECT MAX(latest.indexed_at) FROM staging_work latest WHERE latest.execution_id = staging.execution_id AND latest.doi = staging.doi)
           AND staging.staging_key = (SELECT MIN(winner.staging_key) FROM staging_work winner WHERE winner.execution_id = staging.execution_id AND winner.doi = staging.doi AND winner.indexed_at = staging.indexed_at)
           AND (target.source_indexed_at < staging.indexed_at OR (target.source_indexed_at = staging.indexed_at AND target.content_hash <> staging.content_hash))),
       (SELECT COUNT(*) FROM sync_error WHERE execution_id = execution.id AND status = 'OPEN'),
       execution.business_status,
       execution.sync_contract_hash,
       execution.canonical_version
FROM sync_execution execution
WHERE execution.request_id = '${REQUEST_ID}'
LIMIT 1;")
IFS=$'\t' read -r execution_id batch_execution_id mode created_from created_until max_items expected \
  pages reported_total stop_reason page_cap staging distinct_doi inserted superseded no_op conflicts \
  index_advanced updated validation_errors target_count checksum_mismatches open_errors status \
  contract_hash canonical_version <<< "$result"
accounted=$((inserted + superseded + no_op + conflicts + index_advanced + updated + validation_errors))
schema_version=$(demo_mysql_query open_metadata_live_demo \
  "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1;")

if [[ "$mode" != "BACKFILL" || "$created_from" != "2026-08-01" \
   || "$created_until" != "2026-08-08" || "$max_items" != "10000" \
   || "$expected" != "10000" || "$pages" != "10" || "$reported_total" -lt "10000" \
   || "$stop_reason" != "MAX_ITEMS" || "$page_cap" != "12" \
   || "$staging" != "10000" || "$accounted" != "10000" \
   || "$target_count" != "$distinct_doi" || "$checksum_mismatches" != "0" \
   || "$open_errors" != "0" || "$status" != "COMPLETED" || "$schema_version" != "6" ]]; then
  echo "Live Crossref verification failed: $result" >&2
  exit 1
fi

OUTPUT_DIR="${DEMO_OUTPUT_DIR:-build/jenkins}"
mkdir -p "$OUTPUT_DIR"
json="$OUTPUT_DIR/live-crossref-${REQUEST_ID}.json"
markdown="$OUTPUT_DIR/live-crossref-${REQUEST_ID}.md"
printf '{\n  "schema_version": "%s",\n  "request_id": "%s",\n  "sync_execution_id": "%s",\n  "batch_execution_id": %s,\n  "mode": "%s",\n  "created_from": "%s",\n  "created_until": "%s",\n  "max_items": %s,\n  "pages_fetched": %s,\n  "reported_total": %s,\n  "stop_reason": "%s",\n  "page_safety_cap": %s,\n  "expected_count": %s,\n  "staging_count": %s,\n  "distinct_doi_count": %s,\n  "inserted_count": %s,\n  "superseded_count": %s,\n  "no_op_count": %s,\n  "conflict_count": %s,\n  "index_advanced_count": %s,\n  "updated_count": %s,\n  "validation_error_count": %s,\n  "accounted_count": %s,\n  "target_count": %s,\n  "checksum_mismatches": %s,\n  "open_errors": %s,\n  "sync_contract_hash": "%s",\n  "canonical_version": %s,\n  "status": "%s"\n}\n' \
  "$schema_version" "$REQUEST_ID" "$execution_id" "$batch_execution_id" "$mode" \
  "$created_from" "$created_until" "$max_items" "$pages" "$reported_total" "$stop_reason" \
  "$page_cap" "$expected" "$staging" "$distinct_doi" "$inserted" "$superseded" "$no_op" \
  "$conflicts" "$index_advanced" "$updated" "$validation_errors" "$accounted" "$target_count" \
  "$checksum_mismatches" "$open_errors" "$contract_hash" "$canonical_version" "$status" > "$json"
printf '# Live Crossref 10K\n\n| Evidence | Value |\n|---|---:|\n| Request ID | %s |\n| Sync execution | %s |\n| Batch execution | %s |\n| Range | %s through %s |\n| Pages / cap | %s / %s |\n| Stop reason | %s |\n| Reported total | %s |\n| Expected / staging | %s / %s |\n| Distinct DOI / target | %s / %s |\n| Accounted | %s |\n| Checksum mismatches | %s |\n| Open errors | %s |\n| Schema / canonical version | %s / %s |\n| Status | %s |\n' \
  "$REQUEST_ID" "$execution_id" "$batch_execution_id" "$created_from" "$created_until" \
  "$pages" "$page_cap" "$stop_reason" "$reported_total" "$expected" "$staging" \
  "$distinct_doi" "$target_count" "$accounted" "$checksum_mismatches" "$open_errors" \
  "$schema_version" "$canonical_version" "$status" > "$markdown"
echo "Live Crossref 10K verification passed"
