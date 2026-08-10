#!/usr/bin/env bash
set -euo pipefail

[[ "$(id -u)" == "0" ]] || { echo "Jenkins Home bootstrap requires root" >&2; exit 1; }
legacy_job=/var/jenkins_home/jobs/open-metadata-sync-demo-crossref
public_job=/var/jenkins_home/jobs/open-metadata-sync-demo
if [[ -e "$legacy_job" && -e "$public_job" ]]; then
  echo "Both public demo job names exist" >&2
  exit 1
fi
if [[ -e "$legacy_job" ]]; then
  mv "$legacy_job" "$public_job"
fi
source_file=/usr/share/jenkins/ref/init.groovy.d/security-and-jobs.groovy.override
target_file=/var/jenkins_home/init.groovy.d/security-and-jobs.groovy
install -d -o jenkins -g jenkins -m 0755 "$(dirname "$target_file")"
install -o jenkins -g jenkins -m 0644 "$source_file" "$target_file"
cmp "$source_file" "$target_file"
echo "Jenkins Home init bootstrap verified"
