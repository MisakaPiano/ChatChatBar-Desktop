#!/usr/bin/env bash
# Cloud-only test companion. It never uploads server/client data or credentials.
set -euo pipefail
test "$#" -eq 4
test "${GITHUB_ACTIONS:-}" = true
test "${RUNNER_OS:-}" = Linux
test "${GITHUB_REPOSITORY:-}" = MisakaPiano/ChatChatBar-Desktop
test "$(uname -m)" = x86_64
: "${RUNNER_TEMP:?}"

source_dir=$(realpath -- "$1")
evidence_dir=$(realpath -m -- "$2")
source_sha=$3
fixture_sha=$4
test "$source_sha" = 6ab083be4ee3de70757b06ea75ba49a77c558385
[[ "$fixture_sha" =~ ^[0-9a-f]{64}$ ]]
test "$source_dir" = "$1"
test "$evidence_dir" = "$2"
test "$(git -C "$source_dir" rev-parse --show-toplevel)" = "$source_dir"
test "$(git -C "$source_dir" rev-parse HEAD)" = "$source_sha"
case "$evidence_dir/" in "$source_dir/"*) exit 1 ;; esac
mkdir -p -- "$evidence_dir"
test "$(realpath -- "$evidence_dir")" = "$evidence_dir"

driver=app/sharedCore/src/test/kotlin/com/example/chatbar/domain/syncStoreProof/RealWebDavInteropSmokeTest.kt
report=app/sharedCore/build/test-results/test/TEST-com.example.chatbar.domain.syncStoreProof.RealWebDavInteropSmokeTest.xml
check_source() {
  test "$(git -C "$source_dir" rev-parse HEAD)" = "$source_sha"
  test "$(git -C "$source_dir" status --porcelain --untracked-files=all)" = "?? $driver"
  test -f "$source_dir/$driver" && test ! -L "$source_dir/$driver"
  test "$(realpath -- "$source_dir/$driver")" = "$source_dir/$driver"
  printf '%s  %s\n' "$fixture_sha" "$source_dir/$driver" | sha256sum --check --strict -
}
check_source
test ! -e "$source_dir/$report" && test ! -L "$source_dir/$report"
for name in rclone-version.txt rclone-archive-manifest.json real-webdav-method-counts.json; do
  test ! -e "$evidence_dir/$name" && test ! -L "$evidence_dir/$name"
done

runner_temp=$(realpath -- "$RUNNER_TEMP")
case_root=$(mktemp -d "$runner_temp/ccb-rclone-interop-XXXXXX")
mkdir "$case_root/server" "$case_root/cache"
touch "$case_root/empty.conf"
version=v1.76.0
archive=rclone-v1.76.0-linux-amd64.zip
archive_sha=b00f00b3266c3764413afe599d3ec316f6f43b3e0ea8a0bc1d186d2133e46df7
download_url="https://github.com/rclone/rclone/releases/download/$version/$archive"
curl --fail --location --proto '=https' --proto-redir '=https' --tlsv1.2 \
  --connect-timeout 15 --max-time 180 \
  "$download_url" -o "$case_root/$archive"
printf '%s  %s\n' "$archive_sha" "$case_root/$archive" | sha256sum --check --strict -
# Only the authenticated fixed official archive is unpacked or executed.
unzip -q "$case_root/$archive" -d "$case_root/binary"
rclone="$case_root/binary/rclone-v1.76.0-linux-amd64/rclone"
test -f "$rclone" && test ! -L "$rclone" && test -x "$rclone"
"$rclone" version > "$evidence_dir/rclone-version.txt"
test "$(head -n 1 "$evidence_dir/rclone-version.txt")" = 'rclone v1.76.0'
python3 - "$case_root/$archive" "$evidence_dir/rclone-archive-manifest.json" <<'PY'
from pathlib import Path
import hashlib
import json
import sys
archive = Path(sys.argv[1])
digest = hashlib.sha256()
with archive.open('rb') as stream:
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(chunk)
assert digest.hexdigest() == 'b00f00b3266c3764413afe599d3ec316f6f43b3e0ea8a0bc1d186d2133e46df7'
assert archive.stat().st_size > 0
value = {'kind': 'CCB_RCLONE_PINNED_ARCHIVE', 'version': 'v1.76.0',
         'filename': 'rclone-v1.76.0-linux-amd64.zip',
         'downloadUrl': 'https://github.com/rclone/rclone/releases/download/v1.76.0/rclone-v1.76.0-linux-amd64.zip',
         'bytes': archive.stat().st_size, 'sha256': digest.hexdigest()}
with Path(sys.argv[2]).open('x', encoding='utf-8', newline='\n') as output:
    json.dump(value, output, indent=2)
    output.write('\n')
PY

export CCB_REAL_DAV_PROOF=true
export CCB_REAL_DAV_URL=http://127.0.0.1:18765/
export CCB_REAL_DAV_USER=synthetic-ccb-ci
export CCB_REAL_DAV_PASS
CCB_REAL_DAV_PASS=$(python3 -c 'import secrets; print(secrets.token_hex(24))')
export CCB_REAL_DAV_METHOD_EVIDENCE="$evidence_dir/real-webdav-method-counts.json"
server_pid=''
own_server_alive() {
  # Include a stopped child, but never a completed job whose PID could be reused.
  { jobs -pr; jobs -ps; } | grep -Fxq -- "$server_pid"
}
stop_server() {
  local original_status=$?
  trap - EXIT
  if test -n "$server_pid"; then
    # The shell job table identifies only our child, never a PID search.
    if own_server_alive; then kill -- "$server_pid" 2>/dev/null || true; fi
    for attempt in $(seq 1 50); do
      if ! own_server_alive; then break; fi
      sleep 0.1
    done
    if own_server_alive; then kill -KILL -- "$server_pid" 2>/dev/null || true; fi
    wait "$server_pid" 2>/dev/null || true
  fi
  exit "$original_status"
}
trap stop_server EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# The server root/config/cache/log are private runner-temporary data, not evidence.
"$rclone" serve webdav "$case_root/server" --addr 127.0.0.1:18765 \
  --user "$CCB_REAL_DAV_USER" --pass "$CCB_REAL_DAV_PASS" \
  --config "$case_root/empty.conf" --cache-dir "$case_root/cache" \
  --vfs-cache-mode off --log-level INFO --log-file "$case_root/server.log" &
server_pid=$!
ready=false
for attempt in $(seq 1 60); do
  kill -0 "$server_pid"
  status=$(curl --silent --output /dev/null --write-out '%{http_code}' --proxy '' \
    --connect-timeout 1 --max-time 2 --user "$CCB_REAL_DAV_USER:$CCB_REAL_DAV_PASS" \
    --request PROPFIND --header 'Depth: 0' "$CCB_REAL_DAV_URL" || true)
  if test "$status" = 207; then ready=true; break; fi
  sleep 0.25
done
test "$ready" = true
check_source
test ! -e "$source_dir/$report" && test ! -L "$source_dir/$report"
cd "$source_dir/app"
bash ./gradlew :sharedCore:test \
  --tests 'com.example.chatbar.domain.syncStoreProof.RealWebDavInteropSmokeTest.realServerMethodsAndColdDifferentLocalRoutePreserveCompleteOpaquePayload' \
  --no-build-cache --rerun-tasks --no-daemon --stacktrace
check_source
# The Python control copies the exact original XML and validates 1 test / 0F/E/S.
# This script deliberately creates no summary PASS and uploads nothing itself.
