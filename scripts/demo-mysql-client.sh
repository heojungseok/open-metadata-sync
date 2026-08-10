#!/usr/bin/env bash

demo_validate_database_boundary() {
  : "${DB_HOST:?DB_HOST is required}"
  : "${DB_PORT:?DB_PORT is required}"
  : "${DB_USERNAME:?DB_USERNAME is required}"
  : "${DB_PASSWORD:?DB_PASSWORD is required}"

  if [[ "${DEMO_RUNTIME:-}" == "container" ]]; then
    [[ "$DB_HOST" == "mysql" && "$DB_PORT" == "3306" ]] || {
      echo "Public demo requires mysql:3306" >&2
      return 1
    }
    command -v mysql >/dev/null
  else
    [[ "$DB_HOST" == "127.0.0.1" || "$DB_HOST" == "localhost" ]] || {
      echo "Local demo requires a loopback database" >&2
      return 1
    }
    [[ "$DB_PORT" == "3308" ]] || {
      echo "Local demo requires port 3308" >&2
      return 1
    }
    DEMO_DB_CONTAINER="${DEMO_DB_CONTAINER:-open-metadata-sync-demo-mysql}"
    [[ "$DEMO_DB_CONTAINER" == "open-metadata-sync-demo-mysql" ]] || {
      echo "Unexpected local demo container" >&2
      return 1
    }
    export DEMO_DB_CONTAINER
    command -v docker >/dev/null
  fi
}

demo_mysql_query() {
  local database=$1
  local query=$2
  if [[ "${DEMO_RUNTIME:-}" == "container" ]]; then
    MYSQL_PWD="$DB_PASSWORD" mysql --protocol=TCP --batch --skip-column-names \
      -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USERNAME" "$database" -e "$query"
  else
    printf '%s\n' "$DB_PASSWORD" | docker exec -i "$DEMO_DB_CONTAINER" /bin/bash -c '
      IFS= read -r MYSQL_PWD
      export MYSQL_PWD
      exec mysql --batch --skip-column-names -u"$1" "$2" -e "$3"
    ' _ "$DB_USERNAME" "$database" "$query"
  fi
}

demo_mysql_stdin() {
  local database=$1
  if [[ "${DEMO_RUNTIME:-}" == "container" ]]; then
    MYSQL_PWD="$DB_PASSWORD" mysql --protocol=TCP \
      -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USERNAME" "$database"
  else
    { printf '%s\n' "$DB_PASSWORD"; cat; } | docker exec -i "$DEMO_DB_CONTAINER" /bin/bash -c '
      IFS= read -r MYSQL_PWD
      export MYSQL_PWD
      exec mysql -u"$1" "$2"
    ' _ "$DB_USERNAME" "$database"
  fi
}

demo_live_data_hash() {
  if [[ "${DEMO_RUNTIME:-}" == "container" ]]; then
    MYSQL_PWD="$DB_PASSWORD" mysqldump --protocol=TCP --single-transaction \
      --skip-comments --compact --no-create-info --no-tablespaces --skip-triggers --skip-extended-insert \
      -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USERNAME" open_metadata_live_demo \
      | sha256sum | awk '{print $1}'
  else
    printf '%s\n' "$DB_PASSWORD" | docker exec -i "$DEMO_DB_CONTAINER" /bin/bash -c '
      IFS= read -r MYSQL_PWD
      export MYSQL_PWD
      exec mysqldump --single-transaction --skip-comments --compact --no-create-info \
        --no-tablespaces --skip-triggers --skip-extended-insert -u"$1" open_metadata_live_demo
    ' _ "$DB_USERNAME" | shasum -a 256 | awk '{print $1}'
  fi
}

demo_verify_database_sentinel() {
  local database=$1
  local expected_uuid=${2:-${DEMO_DB_SENTINEL_UUID:-00000000-0000-0000-0000-00000000d000}}
  local expected_account=${3:-open_metadata@%}
  local expected_name=${4:-open-metadata-sync-public-demo}
  local actual
  actual=$(demo_mysql_query "$database" \
    "SELECT CONCAT(CURRENT_USER(), '|', environment_uuid, '|', environment_name) FROM demo_environment_guard LIMIT 1;")
  if [[ "$actual" != "$expected_account|$expected_uuid|$expected_name" ]]; then
    echo "Demo database sentinel mismatch for $database" >&2
    return 1
  fi
}
