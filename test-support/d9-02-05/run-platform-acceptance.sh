#!/usr/bin/env bash
set -euo pipefail

# Dedicated disposable emulator + PostgreSQL service. Never point this at enrolled user data.
export D9_PLATFORM_DIRECTORY="$PWD/build/d90205-platform"
mkdir -p "$D9_PLATFORM_DIRECTORY"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -keyout "$D9_PLATFORM_DIRECTORY/key.pem" \
  -out "$D9_PLATFORM_DIRECTORY/cert.pem" -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost' 2>/dev/null
chmod 600 "$D9_PLATFORM_DIRECTORY/key.pem"
printf 'd90205-platform-%s' "$(cat /proc/sys/kernel/random/uuid)" > "$D9_PLATFORM_DIRECTORY/account.txt"
printf 'd90205-platform-%s' "$(cat /proc/sys/kernel/random/uuid)" > "$D9_PLATFORM_DIRECTORY/space.txt"
export SYNC_DATABASE_URL="$SYNC_TEST_DATABASE_URL"
export SYNC_DATABASE_USER="$SYNC_TEST_DATABASE_USER"
export SYNC_DATABASE_PASSWORD="$SYNC_TEST_DATABASE_PASSWORD"
export SYNC_PORT=18444 SYNC_BIND_HOST=127.0.0.1 SYNC_ADMIN_TOKEN=d90205-disposable-admin
./gradlew :server:sync:installDist --no-daemon
server_pid=''
proxy_pid=''
cleanup() {
  if [[ -n "$server_pid" ]]; then kill "$server_pid" || true; fi
  if [[ -n "$proxy_pid" ]]; then kill "$proxy_pid" || true; fi
  # Private fixture state must never be uploaded as a CI artifact.
  rm -f "$D9_PLATFORM_DIRECTORY/key.pem"
}
trap cleanup EXIT
start_server() {
  server/sync/build/install/sync/bin/sync > "$D9_PLATFORM_DIRECTORY/server.log" 2>&1 &
  server_pid=$!
  for attempt in $(seq 1 60); do
    if curl --silent --fail http://127.0.0.1:18444/health >/dev/null; then return; fi
    sleep 1
  done
  echo 'Acceptance relay did not become healthy' >&2
  exit 1
}
start_server
python3 test-support/d9-02-05/tls_proxy.py "$D9_PLATFORM_DIRECTORY/cert.pem" "$D9_PLATFORM_DIRECTORY/key.pem" \
  > "$D9_PLATFORM_DIRECTORY/proxy.log" 2>&1 &
proxy_pid=$!
for attempt in $(seq 1 60); do
  if curl --silent --fail --cacert "$D9_PLATFORM_DIRECTORY/cert.pem" https://localhost:18445/health >/dev/null; then break; fi
  sleep 1
done
phase() {
  D9_PLATFORM_PHASE="$1" ./gradlew :shared:application:desktopTest --tests '*D9PlatformRelayAcceptanceTest' --no-daemon --no-configuration-cache
  cp shared/application/build/test-results/desktopTest/TEST-dev.agenticscheduler.application.sync.D9PlatformRelayAcceptanceTest.xml \
    "$D9_PLATFORM_DIRECTORY/$1.xml"
}
phase seed
kill "$server_pid"
wait "$server_pid" || true
start_server # Actual server process restart, same durable PostgreSQL.
phase resume
adb reverse tcp:18445 tcp:18445
./gradlew :apps:android:connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.agenticscheduler.android.D9PlatformRelayInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.d9AcceptanceCertificate="$(base64 -w0 "$D9_PLATFORM_DIRECTORY/cert.pem")" \
  -Pandroid.testInstrumentationRunnerArguments.d9AcceptanceAccount="$(cat "$D9_PLATFORM_DIRECTORY/account.txt")" \
  -Pandroid.testInstrumentationRunnerArguments.d9AcceptanceSpace="$(cat "$D9_PLATFORM_DIRECTORY/space.txt")"
phase verify
python3 - <<'PY'
import base64, os, subprocess
database = os.environ['SYNC_TEST_DATABASE_URL'].removeprefix('jdbc:')
env = dict(os.environ, PGPASSWORD=os.environ['SYNC_TEST_DATABASE_PASSWORD'])
def query(sql):
    return subprocess.check_output(['psql', database, '-U', os.environ['SYNC_TEST_DATABASE_USER'], '-At', '-c', sql], env=env, text=True)
tables = query("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'").splitlines()
canaries = ['D90205-PLATFORM-TITLE-CANARY', 'D90205-DESKTOP-MESSAGE-CANARY', 'D90205-ANDROID-MESSAGE-CANARY', base64.urlsafe_b64encode(bytes(range(11, 43))).decode().rstrip('=')]
for table in tables:
    rows = query('SELECT to_jsonb(r)::text FROM "' + table.replace('"', '""') + '" r')
    assert not any(value in rows for value in canaries), 'Opaque platform relay leaked a canary in ' + table
print('D9 platform public-table canary scan PASS; tables=', len(tables))
PY
