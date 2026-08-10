#!/usr/bin/env bash
set -euo pipefail
umask 077

: "${RECOVERY_BUNDLE:?RECOVERY_BUNDLE is required}"
: "${RECOVERY_KEY_FILE:?RECOVERY_KEY_FILE is required}"
: "${RECOVERY_PUBLIC_KEY_FILE:?RECOVERY_PUBLIC_KEY_FILE is required}"
[[ -d "$RECOVERY_BUNDLE" ]] || { echo "Recovery bundle is missing" >&2; exit 1; }
[[ -s "$RECOVERY_KEY_FILE" ]] || { echo "Recovery key file is missing or empty" >&2; exit 1; }
[[ -s "$RECOVERY_PUBLIC_KEY_FILE" ]] || { echo "Recovery public key file is missing or empty" >&2; exit 1; }
key_mode=$(stat -f '%Lp' "$RECOVERY_KEY_FILE" 2>/dev/null || stat -c '%a' "$RECOVERY_KEY_FILE")
[[ "$key_mode" == "600" ]] || { echo "Recovery key file mode must be 600" >&2; exit 1; }
openssl pkey -in "$RECOVERY_KEY_FILE" -noout >/dev/null 2>&1 || {
  echo "Recovery key must be an OpenSSL private key" >&2
  exit 1
}
openssl pkey -pubin -in "$RECOVERY_PUBLIC_KEY_FILE" -noout >/dev/null 2>&1 || {
  echo "Recovery public key must be an OpenSSL public key" >&2
  exit 1
}
cmp <(openssl pkey -in "$RECOVERY_KEY_FILE" -pubout 2>/dev/null) \
  <(openssl pkey -pubin -in "$RECOVERY_PUBLIC_KEY_FILE" -pubout 2>/dev/null) >/dev/null || {
  echo "Recovery private and public keys do not match" >&2
  exit 1
}
openssl pkeyutl -verify -rawin -pubin -inkey "$RECOVERY_PUBLIC_KEY_FILE" \
  -in "$RECOVERY_BUNDLE/SHA256SUMS" -sigfile "$RECOVERY_BUNDLE/SHA256SUMS.sig" >/dev/null
openssl pkeyutl -verify -rawin -pubin -inkey "$RECOVERY_PUBLIC_KEY_FILE" \
  -in "$RECOVERY_BUNDLE/recovery-receipt.env" \
  -sigfile "$RECOVERY_BUNDLE/recovery-receipt.env.sig" >/dev/null
grep -Fqx 'recovery_verification=PENDING' "$RECOVERY_BUNDLE/recovery-receipt.env"
manifest_sha=$(shasum -a 256 "$RECOVERY_BUNDLE/SHA256SUMS" | awk '{print $1}')
grep -Fqx "bundle_manifest_sha256=$manifest_sha" "$RECOVERY_BUNDLE/recovery-receipt.env"
(cd "$RECOVERY_BUNDLE" && shasum -a 256 -c SHA256SUMS)

candidate_revision=$(awk -F= '$1 == "candidate_revision" {print $2}' "$RECOVERY_BUNDLE/recovery-receipt.env")
[[ "$candidate_revision" =~ ^[0-9a-f]{40}$ ]] || { echo "Invalid recovery candidate revision" >&2; exit 1; }
secret_dir=$(mktemp -d)
chmod 700 "$secret_dir"
suffix="$$-$(date -u +%s)"
scratch_mysql_volume="open-metadata-sync-live-recovery-mysql-$suffix"
scratch_jenkins_volume="open-metadata-sync-live-recovery-jenkins-$suffix"
scratch_proxy_secret_volume="open-metadata-sync-live-recovery-proxy-secrets-$suffix"
scratch_network="open-metadata-sync-live-recovery-$suffix"
scratch_mysql="open-metadata-sync-live-recovery-mysql-$suffix"
scratch_proxy="open-metadata-sync-live-recovery-proxy-$suffix"
scratch_agent="open-metadata-sync-live-recovery-agent-$suffix"
scratch_controller="open-metadata-sync-live-recovery-controller-$suffix"
scratch_gateway="open-metadata-sync-live-recovery-gateway-$suffix"
cleanup() {
  docker rm -f "$scratch_gateway" "$scratch_controller" "$scratch_agent" \
    "$scratch_proxy" "$scratch_mysql" >/dev/null 2>&1 || true
  docker volume rm "$scratch_mysql_volume" "$scratch_jenkins_volume" \
    "$scratch_proxy_secret_volume" >/dev/null 2>&1 || true
  docker network rm "$scratch_network" >/dev/null 2>&1 || true
  rm -rf "${secret_dir:?}"
}
trap cleanup EXIT

recovery_passphrase() {
  openssl pkey -in "$RECOVERY_KEY_FILE" -outform DER 2>/dev/null \
    | openssl dgst -sha256 -hex | awk '{print $2}'
}
decrypt_file() {
  local name=$1
  openssl enc -d -aes-256-cbc -pbkdf2 -md sha256 \
    -in "$RECOVERY_BUNDLE/$name.enc" -out "$secret_dir/$name" \
    -pass fd:3 3< <(recovery_passphrase)
  chmod 600 "$secret_dir/$name"
}
for name in mysql-password mysql-live-password mysql-root-password jenkins-admin-password \
  agent_ssh_key agent_ssh_key.pub \
  crossref-mailto jenkins-home.tar.gz live-and-replay.sql candidate-images.tar; do
  decrypt_file "$name"
done
chmod 644 "$secret_dir/agent_ssh_key.pub"
replay_password=$(tr -d '\r\n' < "$secret_dir/mysql-password")
live_password=$(tr -d '\r\n' < "$secret_dir/mysql-live-password")
[[ "$replay_password" =~ ^[A-Za-z0-9_-]{32,128}$ ]] || { echo "Invalid replay password" >&2; exit 1; }
[[ "$live_password" =~ ^[A-Za-z0-9_-]{32,128}$ ]] || { echo "Invalid live password" >&2; exit 1; }

docker load -i "$secret_dir/candidate-images.tar" >/dev/null
candidate_images=(
  "open-metadata-sync-demo-controller:$candidate_revision"
  "open-metadata-sync-demo-agent:$candidate_revision"
  "open-metadata-sync-demo-gateway:$candidate_revision"
  "open-metadata-sync-demo-crossref-proxy:$candidate_revision"
)
docker image inspect "${candidate_images[@]}" > "$secret_dir/candidate-images-inspect.json"
python3 - "$RECOVERY_BUNDLE/candidate-images-inspect.json" "$secret_dir/candidate-images-inspect.json" <<'PY'
import json
import sys


def stable(images):
    for image in images:
        metadata = image.get("Metadata")
        if metadata is not None:
            metadata.pop("LastTagTime", None)
        descriptor = image.get("Descriptor")
        if descriptor is not None:
            descriptor.pop("annotations", None)
    return images


with open(sys.argv[1], encoding="utf-8") as source:
    expected = stable(json.load(source))
with open(sys.argv[2], encoding="utf-8") as source:
    actual = stable(json.load(source))
if expected != actual:
    raise SystemExit("Recovered image inspect mismatch")
PY
docker network create "$scratch_network" >/dev/null
docker volume create "$scratch_mysql_volume" >/dev/null
docker volume create "$scratch_jenkins_volume" >/dev/null
docker volume create "$scratch_proxy_secret_volume" >/dev/null
docker run -d --name "$scratch_mysql" --network "$scratch_network" --network-alias mysql \
  -e MYSQL_ROOT_PASSWORD_FILE=/run/secrets/root \
  -v "$secret_dir/mysql-root-password:/run/secrets/root:ro" \
  -v "$scratch_mysql_volume:/var/lib/mysql" \
  mysql:8.4.10@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6 >/dev/null
for _ in {1..60}; do
  if docker exec "$scratch_mysql" /bin/bash -c '
      MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
      export MYSQL_PWD
      exec mysql --batch --skip-column-names -uroot -e "SELECT 1"
    ' >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
docker exec "$scratch_mysql" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
  export MYSQL_PWD
  exec mysql --batch --skip-column-names -uroot -e "SELECT 1"
' >/dev/null
docker exec -i "$scratch_mysql" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
  export MYSQL_PWD
  exec mysql -uroot
' < "$secret_dir/live-and-replay.sql"
docker exec -i "$scratch_mysql" /bin/bash -c '
  MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
  export MYSQL_PWD
  exec mysql -uroot
' <<SQL
CREATE USER IF NOT EXISTS 'open_metadata'@'%' IDENTIFIED BY '${replay_password}';
GRANT ALL PRIVILEGES ON open_metadata.* TO 'open_metadata'@'%';
CREATE USER IF NOT EXISTS 'open_metadata_live_demo'@'%' IDENTIFIED BY '${live_password}';
GRANT ALL PRIVILEGES ON open_metadata_live_demo.* TO 'open_metadata_live_demo'@'%';
SQL

scratch_root_query() {
  docker exec "$scratch_mysql" /bin/bash -c '
    MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
    export MYSQL_PWD
    exec mysql --batch --skip-column-names -uroot -e "$1"
  ' _ "$1"
}
schema_hash() {
  local schema=$1
  scratch_root_query "SET SESSION group_concat_max_len=1000000; SELECT GROUP_CONCAT(CONCAT(TABLE_NAME, '|', COLUMN_NAME, '|', COLUMN_TYPE, '|', IS_NULLABLE, '|', COLUMN_KEY, '|', EXTRA) ORDER BY TABLE_NAME, ORDINAL_POSITION SEPARATOR '\\n') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '$schema';" \
    | shasum -a 256 | awk '{print $1}'
}
data_hash() {
  local schema=$1
  docker exec "$scratch_mysql" /bin/bash -c '
    MYSQL_PWD=$(tr -d "\r\n" < /run/secrets/root)
    export MYSQL_PWD
    exec mysqldump -uroot --single-transaction --skip-comments --compact \
      --no-create-info --skip-triggers "$1"
  ' _ "$schema" | shasum -a 256 | awk '{print $1}'
}
verify_schema() {
  local label=$1
  local schema=$2
  local actual_schema actual_data actual_tables
  actual_schema=$(schema_hash "$schema")
  actual_data=$(data_hash "$schema")
  actual_tables=$(scratch_root_query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$schema';")
  grep -Fqx "${label}_schema_sha256=$actual_schema" "$RECOVERY_BUNDLE/recovery-receipt.env" || {
    echo "Scratch $label schema mismatch" >&2
    exit 1
  }
  grep -Fqx "${label}_data_sha256=$actual_data" "$RECOVERY_BUNDLE/recovery-receipt.env" || {
    echo "Scratch $label data mismatch" >&2
    exit 1
  }
  grep -Fqx "${label}_table_count=$actual_tables" "$RECOVERY_BUNDLE/recovery-receipt.env" || {
    echo "Scratch $label table count mismatch" >&2
    exit 1
  }
}
verify_schema live open_metadata_live_demo
verify_schema replay open_metadata

docker run --rm --user 0:0 --entrypoint /bin/tar \
  -v "$scratch_jenkins_volume:/target" -v "$secret_dir:/backup:ro" \
  "open-metadata-sync-demo-controller:$candidate_revision" \
  -C /target -xzf /backup/jenkins-home.tar.gz
docker run --rm --user 0:0 --entrypoint /usr/local/bin/demo-bootstrap-jenkins-home \
  -v "$scratch_jenkins_volume:/var/jenkins_home" \
  "open-metadata-sync-demo-controller:$candidate_revision"
docker run --rm --entrypoint /bin/bash -v "$scratch_jenkins_volume:/target:ro" \
  "open-metadata-sync-demo-controller:$candidate_revision" -c '
    test -s /target/jobs/open-metadata-sync-demo/config.xml
    test -s /target/jobs/open-metadata-sync-demo-10k/config.xml
    test -s /target/jobs/open-metadata-sync-demo-replay/config.xml
    grep -Fq "<disabled>true</disabled>" /target/jobs/open-metadata-sync-demo-10k/config.xml
    grep -Fq "<disabled>true</disabled>" /target/jobs/open-metadata-sync-demo-replay/config.xml
  '

docker run --rm --user 0 --entrypoint /bin/bash \
  -v "$secret_dir/crossref-mailto:/source:ro" \
  -v "$scratch_proxy_secret_volume:/target" \
  "open-metadata-sync-demo-controller:$candidate_revision" -c '
    cp /source /target/crossref_mailto
    chown 65532:65532 /target/crossref_mailto
    chmod 600 /target/crossref_mailto
  '

docker run -d --name "$scratch_proxy" --network "$scratch_network" --network-alias crossref-proxy \
  -e CROSSREF_MAILTO_FILE=/run/secrets/crossref_mailto \
  -v "$scratch_proxy_secret_volume:/run/secrets:ro" \
  "open-metadata-sync-demo-crossref-proxy:$candidate_revision" >/dev/null
docker run -d --name "$scratch_agent" --network "$scratch_network" --network-alias jenkins-agent \
  --tmpfs /home/jenkins/agent:size=2g,exec,uid=1000,gid=1000,mode=0700 \
  -v "$secret_dir/agent_ssh_key.pub:/run/secrets/agent_ssh_pubkey:ro" \
  "open-metadata-sync-demo-agent:$candidate_revision" >/dev/null
docker run -d --name "$scratch_controller" --network "$scratch_network" --network-alias jenkins-controller \
  -v "$scratch_jenkins_volume:/var/jenkins_home" \
  -v "$secret_dir/agent_ssh_key:/run/secrets/agent_ssh_key:ro" \
  -v "$secret_dir/mysql-password:/run/secrets/demo_mysql_password:ro" \
  -v "$secret_dir/mysql-live-password:/run/secrets/demo_mysql_live_password:ro" \
  -v "$secret_dir/jenkins-admin-password:/run/secrets/jenkins_admin_password:ro" \
  "open-metadata-sync-demo-controller:$candidate_revision" >/dev/null
docker run -d --name "$scratch_gateway" --network "$scratch_network" \
  -e JENKINS_ORIGIN=http://jenkins-controller:8080 \
  "open-metadata-sync-demo-gateway:$candidate_revision" >/dev/null
recovery_ready=0
for _ in {1..90}; do
	if docker exec "$scratch_gateway" python3 -c "
import json, urllib.request
urllib.request.urlopen('http://127.0.0.1:8080/healthz', timeout=2).read()
jobs=json.load(urllib.request.urlopen('http://jenkins-controller:8080/api/json?tree=jobs[name]', timeout=2))
nodes=json.load(urllib.request.urlopen('http://jenkins-controller:8080/computer/api/json?tree=computer[displayName,offline]', timeout=2))
assert {job['name'] for job in jobs['jobs']} == {'open-metadata-sync-demo'}
assert any(node['displayName'] == 'demo-agent' and not node['offline'] for node in nodes['computer'])
" >/dev/null 2>&1 \
      && docker exec "$scratch_agent" /usr/bin/timeout 3 /bin/bash -c '
        set -euo pipefail
        exec 3<>/dev/tcp/crossref-proxy/8080
        printf "GET /healthz HTTP/1.1\r\nHost: crossref-proxy\r\nConnection: close\r\n\r\n" >&3
        IFS= read -r status <&3
        grep -Eq "^HTTP/1[.][01] 200 " <<< "$status"
      ' >/dev/null 2>&1; then
    recovery_ready=1
    break
  fi
  sleep 2
done
[[ "$recovery_ready" == "1" ]] || { echo "Recovered proxy did not become ready" >&2; exit 1; }
docker exec "$scratch_gateway" python3 -c "
import json, urllib.request
nodes=json.load(urllib.request.urlopen('http://jenkins-controller:8080/computer/api/json?tree=computer[displayName,offline]', timeout=2))
assert any(node['displayName'] == 'demo-agent' and not node['offline'] for node in nodes['computer'])
"
docker exec -i "$scratch_gateway" python3 /app/verify_owner_login.py \
  http://127.0.0.1:8081 < "$secret_dir/jenkins-admin-password"
before=$(docker exec "$scratch_gateway" python3 -c "
import json, urllib.request
data=json.load(urllib.request.urlopen('http://jenkins-controller:8080/job/open-metadata-sync-demo/api/json?tree=lastBuild[number]', timeout=2))
print((data.get('lastBuild') or {}).get('number', 0))
")
docker exec "$scratch_gateway" python3 -c "
import urllib.request
body=b'MODE=REPLAY_ERRORS&CHUNK_SIZE=1000'
request=urllib.request.Request('http://127.0.0.1:8080/job/open-metadata-sync-demo/buildWithParameters', data=body, method='POST')
with urllib.request.urlopen(request, timeout=10) as response:
    assert response.status in (200, 201, 202)
"
for _ in {1..600}; do
  result=$(docker exec "$scratch_gateway" python3 -c "
import json, urllib.request
data=json.load(urllib.request.urlopen('http://jenkins-controller:8080/job/open-metadata-sync-demo/api/json?tree=lastBuild[number,building,result,artifacts[fileName]]', timeout=2))
build=data.get('lastBuild') or {}
print(build.get('number', 0), str(build.get('building', True)).lower(), build.get('result') or '-', ','.join(a['fileName'] for a in build.get('artifacts', [])))
")
  read -r number building status artifacts <<< "$result"
  if [[ "$number" -gt "$before" && "$building" == "false" ]]; then
    [[ "$status" == "SUCCESS" || "$status" == "NOT_BUILT" ]] || { echo "Recovered replay smoke failed: $status" >&2; exit 1; }
    [[ "$artifacts" == *crossref-*.json* ]] || { echo "Recovered replay artifact is missing" >&2; exit 1; }
    break
  fi
  sleep 2
done
[[ "${number:-0}" -gt "$before" && ( "${status:-}" == "SUCCESS" || "${status:-}" == "NOT_BUILT" ) ]] || {
  echo "Recovered replay smoke timed out" >&2
  exit 1
}
cmp "$RECOVERY_BUNDLE/mysql-volume-inspect.json" \
  <(docker volume inspect open-metadata-sync-public-demo-mysql-data)
cmp "$RECOVERY_BUNDLE/jenkins-volume-inspect.json" \
  <(docker volume inspect open-metadata-sync-public-demo-jenkins-home)

receipt_tmp="$RECOVERY_BUNDLE/recovery-receipt.env.tmp"
sed 's/^recovery_verification=PENDING$/recovery_verification=PASS/' \
  "$RECOVERY_BUNDLE/recovery-receipt.env" > "$receipt_tmp"
if [[ "$status" == "NOT_BUILT" ]]; then
  expected_live_data=$(sed -n 's/^live_data_sha256=//p' "$RECOVERY_BUNDLE/recovery-receipt.env")
  [[ "$(data_hash open_metadata_live_demo)" == "$expected_live_data" ]] || {
    echo "Recovered no-target replay changed live data" >&2
    exit 1
  }
fi
printf 'verified_at=%s\nrecovery_live_schema=PASS\nrecovery_replay_schema=PASS\nrecovery_jenkins_home=PASS\nrecovery_candidate_images=PASS\nrecovery_replay=%s\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$status" >> "$receipt_tmp"
mv "$receipt_tmp" "$RECOVERY_BUNDLE/recovery-receipt.env"
openssl pkeyutl -sign -rawin -inkey "$RECOVERY_KEY_FILE" \
  -in "$RECOVERY_BUNDLE/recovery-receipt.env" \
  -out "$RECOVERY_BUNDLE/recovery-receipt.env.sig"
echo "Current live demo recovery bundle passed scratch restore verification"
