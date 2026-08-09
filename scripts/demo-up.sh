#!/usr/bin/env bash
set -euo pipefail

: "${DEMO_MYSQL_PASSWORD:?DEMO_MYSQL_PASSWORD is required}"
: "${DEMO_MYSQL_ROOT_PASSWORD:?DEMO_MYSQL_ROOT_PASSWORD is required}"

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"
JAVA_HOME=$(/usr/libexec/java_home -v 21)
export JAVA_HOME

command -v docker >/dev/null
docker compose -f compose.demo.yaml up -d mysql
for _ in {1..30}; do
  if [[ "$(docker inspect -f '{{.State.Health.Status}}' open-metadata-sync-demo-mysql)" == "healthy" ]]; then
    break
  fi
  sleep 2
done
if [[ "$(docker inspect -f '{{.State.Health.Status}}' open-metadata-sync-demo-mysql)" != "healthy" ]]; then
  echo "Demo MySQL did not become healthy" >&2
  exit 1
fi

./gradlew bootJar
for profile in actual benchmark-preflight; do
  DB_HOST=127.0.0.1 DB_PORT=3308 DB_USERNAME=open_metadata DB_PASSWORD="$DEMO_MYSQL_PASSWORD" \
    "$JAVA_HOME/bin/java" -jar build/libs/open-metadata-sync-0.0.1-SNAPSHOT.jar \
      --spring.batch.job.enabled=false --spring.profiles.active="$profile"
done

export MYSQL_PWD="$DEMO_MYSQL_PASSWORD"
docker exec -i -e MYSQL_PWD open-metadata-sync-demo-mysql \
  mysql -uopen_metadata open_metadata < scripts/demo-replay-fixture.sql
curl --fail --silent --show-error http://127.0.0.1:9090/login >/dev/null

mkdir -p build/demo
if [[ "${DEMO_SKIP_TUNNEL:-0}" == "1" ]]; then
  echo "Demo database and fixtures are ready; tunnel skipped"
  exit 0
fi
test -f build/demo/jenkins-security-verified
command -v cloudflared >/dev/null
PID_FILE=build/demo/cloudflared.pid
LOG_FILE=build/demo/cloudflared.log
if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "Quick Tunnel is already running" >&2
  exit 1
fi
cloudflared tunnel --url http://127.0.0.1:9090 --no-autoupdate >"$LOG_FILE" 2>&1 &
echo $! > "$PID_FILE"
for _ in {1..30}; do
  if grep -Eo 'https://[-a-z0-9]+\.trycloudflare\.com' "$LOG_FILE" > build/demo/tunnel-url.txt; then
    cat build/demo/tunnel-url.txt
    exit 0
  fi
  sleep 1
done
echo "Quick Tunnel URL was not reported" >&2
exit 1
