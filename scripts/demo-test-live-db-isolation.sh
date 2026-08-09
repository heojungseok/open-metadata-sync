#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"

suffix="$$-$(date -u +%s)"
network="open-metadata-sync-live-db-test-$suffix"
volume="open-metadata-sync-live-db-test-$suffix"
mysql_container="open-metadata-sync-live-db-test-mysql-$suffix"
secret_dir=$(mktemp -d)
root_password=$(openssl rand -hex 32)
replay_password=$(openssl rand -hex 32)
live_password_a=$(openssl rand -hex 32)
live_password_b=$(openssl rand -hex 32)
printf '%s\n' "$root_password" > "$secret_dir/root"
printf '%s\n' "$replay_password" > "$secret_dir/replay"
printf '%s\n' "$live_password_a" > "$secret_dir/live-a"
printf '%s\n' "$live_password_b" > "$secret_dir/live-b"
printf '[client]\nuser=open_metadata\npassword=%s\n' "$replay_password" > "$secret_dir/replay.cnf"
printf '[client]\nuser=open_metadata_live_demo\npassword=%s\n' "$live_password_b" > "$secret_dir/live.cnf"
chmod 600 "$secret_dir"/*

cleanup() {
  docker rm -f "$mysql_container" >/dev/null 2>&1 || true
  docker volume rm "$volume" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  rm -rf "$secret_dir"
}
trap cleanup EXIT

docker network create "$network" >/dev/null
docker volume create "$volume" >/dev/null
docker run -d --name "$mysql_container" --network "$network" --network-alias mysql \
  -e MYSQL_ROOT_PASSWORD_FILE=/run/secrets/root \
  -v "$secret_dir/root:/run/secrets/root:ro" -v "$volume:/var/lib/mysql" \
  mysql:8.4.10@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6 >/dev/null
for _ in {1..60}; do
  if docker exec "$mysql_container" /bin/bash -c '
      MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
      export MYSQL_PWD
      exec mysql --batch --skip-column-names -uroot -e "SELECT 1"
    ' >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker exec "$mysql_container" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
  export MYSQL_PWD
  exec mysql --batch --skip-column-names -uroot -e "SELECT 1"
' >/dev/null
for _ in {1..60}; do
  if docker run --rm --network "$network" --entrypoint /bin/bash \
      -v "$secret_dir/root:/run/secrets/root:ro" \
      mysql:8.4.10@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6 \
      -c 'MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root); export MYSQL_PWD; mysql --protocol=TCP -hmysql -P3306 -uroot -e "SELECT 1"' \
      >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker run --rm --network "$network" --entrypoint /bin/bash \
  -v "$secret_dir/root:/run/secrets/root:ro" \
  mysql:8.4.10@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6 \
  -c 'MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root); export MYSQL_PWD; mysql --protocol=TCP -hmysql -P3306 -uroot -e "SELECT 1"' \
  >/dev/null

docker exec -i "$mysql_container" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
  export MYSQL_PWD
  exec mysql -uroot
' <<SQL
CREATE DATABASE open_metadata;
CREATE USER 'open_metadata'@'%' IDENTIFIED BY '${replay_password}';
GRANT ALL PRIVILEGES ON open_metadata.* TO 'open_metadata'@'%';
CREATE TABLE open_metadata.replay_guard (id INT PRIMARY KEY);
SQL

bootstrap_live() {
  local live_secret=$1
  docker run --rm --network "$network" --entrypoint /bin/bash \
    -e MYSQL_ROOT_PASSWORD_FILE=/run/secrets/root \
    -e LIVE_DB_PASSWORD_FILE=/run/secrets/live \
    -e DEMO_BOOTSTRAP_ACK=CREATE_LIVE_DEMO \
    -v "$secret_dir/root:/run/secrets/root:ro" \
    -v "$live_secret:/run/secrets/live:ro" \
    -v "$PROJECT_DIR/scripts:/opt/demo/scripts:ro" \
    mysql:8.4.10@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6 \
    /opt/demo/scripts/demo-bootstrap-live-db.sh
}
bootstrap_live "$secret_dir/live-a"

docker exec -i "$mysql_container" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
  export MYSQL_PWD
  exec mysql -uroot
' <<'SQL'
CREATE ROLE 'replay_reader_role';
GRANT SELECT ON open_metadata.* TO 'replay_reader_role';
GRANT 'replay_reader_role' TO 'open_metadata_live_demo'@'%';
SET DEFAULT ROLE ALL TO 'open_metadata_live_demo'@'%';
SQL
bootstrap_live "$secret_dir/live-b"

role_edges=$(docker exec "$mysql_container" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
  export MYSQL_PWD
  exec mysql --batch --skip-column-names -uroot mysql -e \
    "SELECT COUNT(*) FROM role_edges WHERE TO_USER = '\''open_metadata_live_demo'\'' AND TO_HOST = '\''%'\'';"
')
default_roles=$(docker exec "$mysql_container" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
  export MYSQL_PWD
  exec mysql --batch --skip-column-names -uroot mysql -e \
    "SELECT COUNT(*) FROM default_roles WHERE USER = '\''open_metadata_live_demo'\'' AND HOST = '\''%'\'';"
')
[[ "$role_edges" == "0" && "$default_roles" == "0" ]] || {
  echo "Live user retained role or default-role state" >&2
  exit 1
}

docker exec -i "$mysql_container" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
  export MYSQL_PWD
  exec mysql -uroot
' <<'SQL'
CREATE TABLE open_metadata_live_demo.live_guard (id INT PRIMARY KEY);
SQL

expect_denied() {
  local username=$1
  local config=$2
  local statement=$3
  if docker run --rm --network "$network" \
      -v "$config:/run/secrets/client.cnf:ro" \
      mysql:8.4.10@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6 \
      mysql --defaults-extra-file=/run/secrets/client.cnf --protocol=TCP \
        -hmysql -P3306 -u"$username" -e "$statement" >/dev/null 2>&1; then
    echo "Unexpected cross-schema privilege: $username: $statement" >&2
    exit 1
  fi
}

expect_denied open_metadata_live_demo "$secret_dir/live.cnf" "SELECT * FROM open_metadata.replay_guard"
expect_denied open_metadata_live_demo "$secret_dir/live.cnf" "TRUNCATE TABLE open_metadata.replay_guard"
expect_denied open_metadata_live_demo "$secret_dir/live.cnf" "CREATE TABLE open_metadata.forbidden (id INT)"
expect_denied open_metadata_live_demo "$secret_dir/live.cnf" "DROP DATABASE open_metadata"
expect_denied open_metadata "$secret_dir/replay.cnf" "SELECT * FROM open_metadata_live_demo.live_guard"
expect_denied open_metadata "$secret_dir/replay.cnf" "TRUNCATE TABLE open_metadata_live_demo.live_guard"
expect_denied open_metadata "$secret_dir/replay.cnf" "CREATE TABLE open_metadata_live_demo.forbidden (id INT)"
expect_denied open_metadata "$secret_dir/replay.cnf" "DROP DATABASE open_metadata_live_demo"

docker run --rm --network "$network" -v "$secret_dir/live.cnf:/run/secrets/client.cnf:ro" \
  mysql:8.4.10@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6 \
  mysql --defaults-extra-file=/run/secrets/client.cnf --protocol=TCP \
  -hmysql -P3306 -uopen_metadata_live_demo \
  -e "SELECT * FROM open_metadata_live_demo.live_guard" >/dev/null
echo "Live/replay database privilege isolation passed"
