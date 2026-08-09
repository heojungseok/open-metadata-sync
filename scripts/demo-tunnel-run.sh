#!/usr/bin/env bash
set -euo pipefail

TOKEN_FILE=${DEMO_TUNNEL_TOKEN_FILE:-"$HOME/.config/open-metadata-sync-demo/cloudflared-token"}
: "${DEMO_ACCESS_APP_ID:?DEMO_ACCESS_APP_ID is required}"

test -f "$TOKEN_FILE"
test "$(stat -f '%Lp' "$TOKEN_FILE")" = "600"
curl --fail --silent --show-error http://127.0.0.1:9092/healthz >/dev/null

echo "Starting protected tunnel for Access app $DEMO_ACCESS_APP_ID to 127.0.0.1:9092"
exec cloudflared tunnel run --token-file "$TOKEN_FILE"
