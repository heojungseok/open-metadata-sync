#!/usr/bin/env bash
set -euo pipefail

: "${MYSQL_ROOT_PASSWORD_FILE:?MYSQL_ROOT_PASSWORD_FILE is required}"
: "${RECOVERY_RECEIPT_FILE:?RECOVERY_RECEIPT_FILE is required}"
[[ "${DEMO_CLEANUP_ACK:-}" == "DROP_LEGACY_SYNTHETIC_SCHEMA" ]] || {
  echo "Legacy cleanup requires explicit acknowledgement" >&2
  exit 1
}
[[ -f "$RECOVERY_RECEIPT_FILE" ]] || { echo "Recovery receipt is missing" >&2; exit 1; }
grep -Fqx 'recovery_verification=PASS' "$RECOVERY_RECEIPT_FILE" || {
  echo "Recovery rehearsal has not passed" >&2
  exit 1
}

root_password=$(tr -d '\r\n' < "$MYSQL_ROOT_PASSWORD_FILE")
mysql_root=(mysql --protocol=TCP --batch --skip-column-names -hmysql -P3306 -uroot)
query() { MYSQL_PWD="$root_password" "${mysql_root[@]}" -e "$1"; }

sentinel=$(query "SELECT CONCAT(environment_uuid, '|', environment_name) FROM open_metadata_benchmark_preflight.demo_environment_guard LIMIT 1;")
[[ "$sentinel" == '00000000-0000-0000-0000-00000000d000|open-metadata-sync-public-demo' ]] || {
  echo "Legacy schema sentinel mismatch" >&2
  exit 1
}
expected_tables='BATCH_JOB_EXECUTION,BATCH_JOB_EXECUTION_CONTEXT,BATCH_JOB_EXECUTION_PARAMS,BATCH_JOB_EXECUTION_SEQ,BATCH_JOB_INSTANCE,BATCH_JOB_INSTANCE_SEQ,BATCH_STEP_EXECUTION,BATCH_STEP_EXECUTION_CONTEXT,BATCH_STEP_EXECUTION_SEQ,demo_environment_guard,flyway_schema_history,staging_work,sync_chunk_result,sync_error,sync_execution,sync_watermark,sync_window,work'
actual_tables=$(query "SELECT GROUP_CONCAT(TABLE_NAME ORDER BY TABLE_NAME SEPARATOR ',') FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'open_metadata_benchmark_preflight';")
[[ "$actual_tables" == "$expected_tables" ]] || { echo "Legacy table contract mismatch" >&2; exit 1; }

replay_schema=$(query "SET SESSION group_concat_max_len=1000000; SELECT GROUP_CONCAT(CONCAT(TABLE_NAME, '|', COLUMN_NAME, '|', COLUMN_TYPE, '|', IS_NULLABLE, '|', COLUMN_KEY, '|', EXTRA) ORDER BY TABLE_NAME, ORDINAL_POSITION SEPARATOR '\\n') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'open_metadata';" | sha256sum | awk '{print $1}')
grep -Fqx "replay_schema_sha256=$replay_schema" "$RECOVERY_RECEIPT_FILE" || {
  echo "Replay schema does not match the recovery receipt" >&2
  exit 1
}

MYSQL_PWD="$root_password" mysql --protocol=TCP -hmysql -P3306 -uroot <<'SQL'
REVOKE ALL PRIVILEGES ON open_metadata_benchmark_preflight.* FROM 'open_metadata'@'%';
DROP DATABASE open_metadata_benchmark_preflight;
SQL
echo "Legacy synthetic schema removed; replay schema preserved"
