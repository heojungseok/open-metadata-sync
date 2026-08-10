#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/demo-mysql-client.sh"
demo_validate_database_boundary
demo_live_data_hash
