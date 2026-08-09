#!/usr/bin/env bash
set -euo pipefail

: "${MYSQL_ROOT_PASSWORD_FILE:?MYSQL_ROOT_PASSWORD_FILE is required}"
: "${RECOVERY_RECEIPT_FILE:?RECOVERY_RECEIPT_FILE is required}"
: "${RECOVERY_MANIFEST_FILE:?RECOVERY_MANIFEST_FILE is required}"
: "${RECOVERY_MANIFEST_SIGNATURE_FILE:?RECOVERY_MANIFEST_SIGNATURE_FILE is required}"
: "${RECOVERY_RECEIPT_SIGNATURE_FILE:?RECOVERY_RECEIPT_SIGNATURE_FILE is required}"
: "${RECOVERY_KEY_FILE:?RECOVERY_KEY_FILE is required}"
: "${LIVE_VALIDATION_RECEIPT_FILE:?LIVE_VALIDATION_RECEIPT_FILE is required}"
: "${CANDIDATE_REVISION:?CANDIDATE_REVISION is required}"
[[ "${DEMO_CLEANUP_ACK:-}" == "DROP_LEGACY_SYNTHETIC_SCHEMA" ]] || {
  echo "Legacy cleanup requires explicit acknowledgement" >&2
  exit 1
}
grep -Fqx "$CANDIDATE_REVISION" /opt/open-metadata-sync/.demo-infra-revision || {
  echo "Cleanup image revision mismatch" >&2
  exit 1
}
openssl pkey -in "$RECOVERY_KEY_FILE" -noout >/dev/null
openssl pkeyutl -verify -rawin -inkey "$RECOVERY_KEY_FILE" \
  -in "$RECOVERY_MANIFEST_FILE" -sigfile "$RECOVERY_MANIFEST_SIGNATURE_FILE" >/dev/null
openssl pkeyutl -verify -rawin -inkey "$RECOVERY_KEY_FILE" \
  -in "$RECOVERY_RECEIPT_FILE" -sigfile "$RECOVERY_RECEIPT_SIGNATURE_FILE" >/dev/null
cmp "$(dirname "$RECOVERY_RECEIPT_FILE")/live-validation.env" "$LIVE_VALIDATION_RECEIPT_FILE" || {
  echo "Live validation receipt differs from the recovery snapshot" >&2
  exit 1
}
[[ -f "$RECOVERY_RECEIPT_FILE" ]] || { echo "Recovery receipt is missing" >&2; exit 1; }
grep -Fqx 'recovery_verification=PASS' "$RECOVERY_RECEIPT_FILE" || {
  echo "Recovery rehearsal has not passed" >&2
  exit 1
}
grep -Fqx "candidate_revision=$CANDIDATE_REVISION" "$RECOVERY_RECEIPT_FILE" || {
  echo "Recovery receipt candidate mismatch" >&2
  exit 1
}
manifest_sha=$(sha256sum "$RECOVERY_MANIFEST_FILE" | awk '{print $1}')
grep -Fqx "bundle_manifest_sha256=$manifest_sha" "$RECOVERY_RECEIPT_FILE" || {
  echo "Recovery manifest receipt mismatch" >&2
  exit 1
}
(cd "$(dirname "$RECOVERY_MANIFEST_FILE")" && sha256sum -c "$(basename "$RECOVERY_MANIFEST_FILE")")
grep -Fqx 'live_demo_validation=PASS' "$LIVE_VALIDATION_RECEIPT_FILE" || {
  echo "Live validation receipt is missing" >&2
  exit 1
}
grep -Fqx 'validation_scope=deployed' "$LIVE_VALIDATION_RECEIPT_FILE" || {
  echo "Only deployed validation may authorize legacy cleanup" >&2
  exit 1
}
grep -Fqx 'visitor_path=PASS' "$LIVE_VALIDATION_RECEIPT_FILE" || {
  echo "Actual visitor-path evidence is required" >&2
  exit 1
}
grep -Fqx 'otp_access=PASS' "$LIVE_VALIDATION_RECEIPT_FILE" || {
  echo "OTP access evidence is required" >&2
  exit 1
}
grep -Fqx "candidate_revision=$CANDIDATE_REVISION" "$LIVE_VALIDATION_RECEIPT_FILE" || {
  echo "Live validation candidate mismatch" >&2
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

legacy_grant=$(query "SELECT COUNT(*) FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE = CONCAT(CHAR(39), 'open_metadata', CHAR(39), '@', CHAR(39), '%', CHAR(39)) AND TABLE_SCHEMA = 'open_metadata_benchmark_preflight';")
[[ "$legacy_grant" =~ ^[0-9]+$ && "$legacy_grant" -gt 0 ]] || {
  echo "Expected legacy grant is already absent" >&2
  exit 1
}

replay_schema=$(query "SET SESSION group_concat_max_len=1000000; SELECT GROUP_CONCAT(CONCAT(TABLE_NAME, '|', COLUMN_NAME, '|', COLUMN_TYPE, '|', IS_NULLABLE, '|', COLUMN_KEY, '|', EXTRA) ORDER BY TABLE_NAME, ORDINAL_POSITION SEPARATOR '\\n') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'open_metadata';" | sha256sum | awk '{print $1}')
grep -Fqx "replay_schema_sha256=$replay_schema" "$LIVE_VALIDATION_RECEIPT_FILE" || {
  echo "Replay schema changed after deployed validation" >&2
  exit 1
}
replay_table_count=$(query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'open_metadata';")
grep -Fqx "replay_table_count=$replay_table_count" "$LIVE_VALIDATION_RECEIPT_FILE" || {
  echo "Replay table count changed after deployed validation" >&2
  exit 1
}
replay_data=$(MYSQL_PWD="$root_password" mysqldump --protocol=TCP -hmysql -P3306 -uroot \
  --single-transaction --skip-comments --compact --no-create-info --skip-triggers open_metadata \
  | sha256sum | awk '{print $1}')
grep -Fqx "replay_data_sha256=$replay_data" "$LIVE_VALIDATION_RECEIPT_FILE" || {
  echo "Replay data changed after deployed validation" >&2
  exit 1
}

MYSQL_PWD="$root_password" mysql --protocol=TCP -hmysql -P3306 -uroot <<'SQL'
REVOKE ALL PRIVILEGES ON open_metadata_benchmark_preflight.* FROM 'open_metadata'@'%';
DROP DATABASE open_metadata_benchmark_preflight;
SQL
replay_data_after=$(MYSQL_PWD="$root_password" mysqldump --protocol=TCP -hmysql -P3306 -uroot \
  --single-transaction --skip-comments --compact --no-create-info --skip-triggers open_metadata \
  | sha256sum | awk '{print $1}')
[[ "$replay_data_after" == "$replay_data" ]] || {
  echo "Replay data changed during legacy cleanup" >&2
  exit 1
}
echo "Legacy synthetic schema removed; replay schema preserved"
