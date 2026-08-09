#!/usr/bin/env bash
set -euo pipefail

: "${REQUEST_ID:?REQUEST_ID is required}"
: "${SOURCE_EXECUTION_ID:?SOURCE_EXECUTION_ID is required}"
: "${DB_HOST:?DB_HOST is required}"
: "${DB_PORT:?DB_PORT is required}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"
: "${DEMO_REPLAY_RESET_ACK:?DEMO_REPLAY_RESET_ACK is required}"

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
if [[ "$DB_HOST" != "localhost" && "$DB_HOST" != "127.0.0.1" ]]; then
  echo "Replay demo reset requires a loopback database" >&2
  exit 1
fi
if [[ "$DB_PORT" != "3308" ]]; then
  echo "Replay demo reset requires port 3308" >&2
  exit 1
fi
DEMO_DB_CONTAINER="${DEMO_DB_CONTAINER:-open-metadata-sync-demo-mysql}"
if [[ "$DEMO_DB_CONTAINER" != "open-metadata-sync-demo-mysql" ]]; then
  echo "Replay demo reset requires the isolated demo container" >&2
  exit 1
fi
if [[ "$SOURCE_EXECUTION_ID" != "00000000-0000-0000-0000-00000000d001" ]]; then
  echo "Replay demo reset requires the fixed demo source execution" >&2
  exit 1
fi

compose_project=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$DEMO_DB_CONTAINER")
published_address=$(docker inspect --format '{{(index (index .NetworkSettings.Ports "3306/tcp") 0).HostIp}}:{{(index (index .NetworkSettings.Ports "3306/tcp") 0).HostPort}}' "$DEMO_DB_CONTAINER")
data_mount=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Name}}:{{.Destination}}{{end}}{{end}}' "$DEMO_DB_CONTAINER")
if [[ "$compose_project" != "open-metadata-sync-demo" \
   || "$published_address" != "127.0.0.1:3308" \
   || "$data_mount" != "open-metadata-sync-demo-mysql-data:/var/lib/mysql" ]]; then
  echo "Replay demo reset rejected unexpected container topology" >&2
  exit 1
fi

OUTPUT_DIR="${DEMO_OUTPUT_DIR:-build/jenkins}"
mkdir -p "$OUTPUT_DIR"
export MYSQL_PWD="$DB_PASSWORD"

docker exec -i -e MYSQL_PWD "$DEMO_DB_CONTAINER" \
  mysql -u"$DB_USERNAME" open_metadata < scripts/demo-replay-fixture.sql

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

result=$(docker exec -e MYSQL_PWD "$DEMO_DB_CONTAINER" \
  mysql --batch --skip-column-names -u"$DB_USERNAME" open_metadata -e "$query")
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
