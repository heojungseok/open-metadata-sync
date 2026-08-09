#!/usr/bin/env bash
set -euo pipefail

export JENKINS_AGENT_SSH_PUBKEY
JENKINS_AGENT_SSH_PUBKEY=$(cat /run/secrets/agent_ssh_pubkey)
exec /usr/local/bin/setup-sshd
