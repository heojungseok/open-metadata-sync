#!/usr/bin/env bash
set -euo pipefail

[[ "$(id -u)" == "0" ]] || { echo "Jenkins Home bootstrap requires root" >&2; exit 1; }
source_file=/usr/share/jenkins/ref/init.groovy.d/security-and-jobs.groovy.override
target_file=/var/jenkins_home/init.groovy.d/security-and-jobs.groovy
install -d -o jenkins -g jenkins -m 0755 "$(dirname "$target_file")"
install -o jenkins -g jenkins -m 0644 "$source_file" "$target_file"
cmp "$source_file" "$target_file"
echo "Jenkins Home init bootstrap verified"
