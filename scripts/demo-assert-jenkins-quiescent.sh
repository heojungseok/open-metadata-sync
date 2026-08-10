#!/usr/bin/env bash
set -euo pipefail

: "${CANDIDATE_REVISION:?CANDIDATE_REVISION is required}"
[[ "$CANDIDATE_REVISION" =~ ^[0-9a-f]{40}$ ]] || { echo "Invalid candidate revision" >&2; exit 1; }
probe_image="open-metadata-sync-demo-gateway:$CANDIDATE_REVISION"
network=open-metadata-sync-public-demo_app
controller=open-metadata-sync-public-demo-controller

docker run --rm --network "$network" --entrypoint python3 "$probe_image" -c "
import json, urllib.request
queue=json.load(urllib.request.urlopen('http://jenkins-controller:8080/queue/api/json?tree=items[id]', timeout=2))
nodes=json.load(urllib.request.urlopen(
    'http://jenkins-controller:8080/computer/api/json?tree=computer[executors[currentExecutable[url]],oneOffExecutors[currentExecutable[url]]]',
    timeout=2))
jobs=json.load(urllib.request.urlopen(
    'http://jenkins-controller:8080/api/json?tree=jobs[name,builds[building,result]]', timeout=2))
assert queue['items'] == [], queue
for node in nodes['computer']:
    executors=node.get('executors', []) + node.get('oneOffExecutors', [])
    assert all(executor.get('currentExecutable') is None for executor in executors), node
for job in jobs['jobs']:
    assert all(not build.get('building') and build.get('result') is not None
               for build in job.get('builds', [])), job
"

docker exec "$controller" /bin/bash -c '
  set -euo pipefail
  for build in /var/jenkins_home/jobs/*/builds/*/build.xml; do
    [[ -e "$build" ]] || continue
    grep -Eq "<result>(SUCCESS|UNSTABLE|FAILURE|NOT_BUILT|ABORTED)</result>" "$build" || {
      echo "Resumable Jenkins run remains: $build" >&2
      exit 1
    }
  done
'
echo "Jenkins queue, executors, and persisted runs are quiescent"
