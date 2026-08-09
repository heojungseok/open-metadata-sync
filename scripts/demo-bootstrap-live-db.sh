#!/usr/bin/env bash
set -euo pipefail

: "${MYSQL_ROOT_PASSWORD_FILE:?MYSQL_ROOT_PASSWORD_FILE is required}"
: "${LIVE_DB_PASSWORD_FILE:?LIVE_DB_PASSWORD_FILE is required}"
[[ "${DEMO_BOOTSTRAP_ACK:-}" == "CREATE_LIVE_DEMO" ]] || {
  echo "Live DB bootstrap requires DEMO_BOOTSTRAP_ACK=CREATE_LIVE_DEMO" >&2
  exit 1
}

root_password=$(tr -d '\r\n' < "$MYSQL_ROOT_PASSWORD_FILE")
live_password=$(tr -d '\r\n' < "$LIVE_DB_PASSWORD_FILE")
[[ -n "$root_password" ]] || { echo "Empty MySQL root password" >&2; exit 1; }
[[ "$live_password" =~ ^[A-Za-z0-9_-]{32,128}$ ]] || {
  echo "Live DB password must be a 32-128 character generated token" >&2
  exit 1
}

MYSQL_PWD="$root_password" mysql --protocol=TCP -hmysql -P3306 -uroot <<SQL
CREATE DATABASE IF NOT EXISTS open_metadata_live_demo
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'open_metadata_live_demo'@'%' IDENTIFIED BY '${live_password}';
ALTER USER 'open_metadata_live_demo'@'%' IDENTIFIED BY '${live_password}';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'open_metadata_live_demo'@'%';
GRANT ALL PRIVILEGES ON open_metadata_live_demo.* TO 'open_metadata_live_demo'@'%';
SQL

unexpected=$(MYSQL_PWD="$root_password" mysql --protocol=TCP --batch --skip-column-names \
  -hmysql -P3306 -uroot information_schema -e "
SELECT COUNT(*) FROM SCHEMA_PRIVILEGES
WHERE GRANTEE = CONCAT(CHAR(39), 'open_metadata_live_demo', CHAR(39), '@', CHAR(39), '%', CHAR(39))
  AND TABLE_SCHEMA <> 'open_metadata_live_demo';")
[[ "$unexpected" == "0" ]] || { echo "Unexpected live DB grants" >&2; exit 1; }
echo "Live DB bootstrap verified"
