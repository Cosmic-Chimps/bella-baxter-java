#!/usr/bin/env bash
# test-samples.sh — Run and validate all Bella Baxter Java SDK samples.
#
# Usage:  ./test-samples.sh <api-key>
#         ./test-samples.sh bax-myKeyId-mySecret
#
# Samples tested:
#   01-dotenv-file    — bella secrets get -o .env → java -jar
#   02-process-inject — bella run -- "$JAVA" -jar
#   03-spring-boot    — bella exec -- "$JAVA" -jar (Spring Boot server, curl validation)
#   04-quarkus        — bella exec -- "$JAVA" -jar (Quarkus server, curl validation)

set -uo pipefail

# ─── Java version ─────────────────────────────────────────────────────────────
# Require Java 21 (class file version 65.0); prefer SDKMAN's 21.0.10-amzn
JAVA_21="$HOME/.sdkman/candidates/java/21.0.10-amzn/bin/java"
if [[ -x "$JAVA_21" ]]; then
  JAVA="$JAVA_21"
  export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.10-amzn"
  export PATH="$JAVA_HOME/bin:$PATH"
else
  JAVA="java"
fi

# ─── Paths ──────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SAMPLES_DIR="$SCRIPT_DIR/samples"
DEMO_ENV_FILE="$SCRIPT_DIR/../../../demo.env"

# ─── Arguments ──────────────────────────────────────────────────────────────
BELLA_API_KEY="${1:-}"
if [[ -z "$BELLA_API_KEY" ]]; then
  echo "Usage: $0 <api-key>   e.g. $0 bax-myKeyId-mySecret"
  exit 1
fi
if [[ ! -f "$DEMO_ENV_FILE" ]]; then
  echo "demo.env not found: $DEMO_ENV_FILE"
  exit 1
fi

# ─── Config ─────────────────────────────────────────────────────────────────
export BELLA_BAXTER_URL="http://localhost:5522"
SERVER_PORT_03=8099
SERVER_PORT_04=8098
SERVER_STARTUP_TIMEOUT=60

# ─── Expected values from demo.env ──────────────────────────────────────────
get_env() { grep -m1 "^${1}=" "$DEMO_ENV_FILE" | cut -d'=' -f2-; }

# APP_CONFIG is stored with outer double-quotes in dotenv; strip them + unescape
raw_app_config="$(get_env APP_CONFIG)"
raw_app_config="${raw_app_config#\"}"
raw_app_config="${raw_app_config%\"}"
EXP_APP_CONFIG="${raw_app_config//\\\"/\"}"   # unescape \" → "

EXP_PORT="$(get_env PORT)"
EXP_DB_URL="$(get_env DATABASE_URL)"
EXP_API_KEY="$(get_env EXTERNAL_API_KEY)"
EXP_GLEAP_KEY="$(get_env GLEAP_API_KEY)"
EXP_ENABLE_FEATURES="$(get_env ENABLE_FEATURES)"
EXP_APP_ID="$(get_env APP_ID)"
EXP_CONN_STRING="$(get_env ConnectionStrings__Postgres)"

# ─── Tracking ────────────────────────────────────────────────────────────────
PASS=0
FAIL=0
RESULTS=()

pass() {
  printf "  \xE2\x9C\x85 %s\n" "$1"
  RESULTS=("${RESULTS[@]+"${RESULTS[@]}"}" "PASS: $1")
  PASS=$((PASS + 1))
}
fail() {
  printf "  \xE2\x9D\x8C %s -- %s\n" "$1" "$2"
  RESULTS=("${RESULTS[@]+"${RESULTS[@]}"}" "FAIL: $1 -- $2")
  FAIL=$((FAIL + 1))
}
section() { printf "\n\xe2\x94\x80\xe2\x94\x80\xe2\x94\x80 %s %s\n" "$1" "$(printf '\xe2\x94\x80%.0s' {1..50})"; }

check() {
  local name="$1" output="$2" pattern="$3"
  if printf '%s' "$output" | grep -qF "$pattern"; then
    pass "$name"
  else
    fail "$name" "expected '$pattern'"
  fi
}

# Used by CLI samples (01, 02) — output is KEY=value lines
check_all_secrets() {
  local prefix="$1" output="$2"
  check "$prefix: PORT"                        "$output" "PORT=$EXP_PORT"
  check "$prefix: DATABASE_URL"                "$output" "DATABASE_URL=$EXP_DB_URL"
  check "$prefix: EXTERNAL_API_KEY"            "$output" "EXTERNAL_API_KEY=$EXP_API_KEY"
  check "$prefix: GLEAP_API_KEY"               "$output" "GLEAP_API_KEY=$EXP_GLEAP_KEY"
  check "$prefix: ENABLE_FEATURES"             "$output" "ENABLE_FEATURES=$EXP_ENABLE_FEATURES"
  check "$prefix: APP_ID"                      "$output" "APP_ID=$EXP_APP_ID"
  check "$prefix: ConnectionStrings__Postgres" "$output" "ConnectionStrings__Postgres=$EXP_CONN_STRING"
  check "$prefix: APP_CONFIG"                  "$output" "APP_CONFIG=$EXP_APP_CONFIG"
}

# Used by server samples (03, 04) — output is a JSON response body
check_server_secrets() {
  local prefix="$1" response="$2"
  check "$prefix PORT"                        "$response" "$EXP_PORT"
  check "$prefix DATABASE_URL"                "$response" "$EXP_DB_URL"
  check "$prefix EXTERNAL_API_KEY"            "$response" "$EXP_API_KEY"
  check "$prefix GLEAP_API_KEY"               "$response" "$EXP_GLEAP_KEY"
  check "$prefix ENABLE_FEATURES"             "$response" "$EXP_ENABLE_FEATURES"
  check "$prefix APP_ID"                      "$response" "$EXP_APP_ID"
  check "$prefix ConnectionStrings__Postgres" "$response" "$EXP_CONN_STRING"
  check "$prefix APP_CONFIG"                  "$response" "setting1"
}

# ─── Server helpers ──────────────────────────────────────────────────────────
cleanup_port() {
  local port="$1"
  local pids
  pids="$(lsof -ti :"$port" 2>/dev/null)" || true
  if [[ -n "$pids" ]]; then
    while IFS= read -r pid; do
      kill "$pid" 2>/dev/null || true
    done <<< "$pids"
    sleep 1
  fi
}

wait_for_server() {
  local url="$1" timeout="$2"
  local i=0
  while [[ $i -lt $timeout ]]; do
    if curl -sf "$url" > /dev/null 2>&1; then
      return 0
    fi
    sleep 1
    i=$((i + 1))
  done
  return 1
}

summary_and_exit() {
  printf "\n\xe2\x94\x80\xe2\x94\x80\xe2\x94\x80 Summary %s\n" "$(printf '\xe2\x94\x80%.0s' {1..50})"
  for r in "${RESULTS[@]+"${RESULTS[@]}"}"; do
    echo "  $r"
  done
  printf "\nPASS: %d  FAIL: %d  TOTAL: %d\n" "$PASS" "$FAIL" "$((PASS + FAIL))"
  [[ $FAIL -eq 0 ]] && exit 0 || exit 1
}

# ─── Auth ────────────────────────────────────────────────────────────────────
section "Authentication"
bella login --api-key "$BELLA_API_KEY" > /dev/null 2>&1
if [[ $? -eq 0 ]]; then
  pass "bella login --api-key"
else
  fail "bella login --api-key" "login failed — cannot continue"
  exit 1
fi

# ─── Build SDK into local repo ───────────────────────────────────────────────
section "Build SDK"
pushd "$SCRIPT_DIR" > /dev/null
if mvn install -q -DskipTests > /dev/null 2>&1; then
  pass "mvn install bella-baxter-sdk"
else
  fail "mvn install bella-baxter-sdk" "SDK build failed — cannot continue"
  exit 1
fi
popd > /dev/null

# ─── Build all samples ────────────────────────────────────────────────────────
section "Build"
for sample in 01-dotenv-file 02-process-inject 03-spring-boot 04-quarkus; do
  pushd "$SAMPLES_DIR/$sample" > /dev/null
  if mvn package -q -DskipTests > /dev/null 2>&1; then
    pass "mvn package $sample"
  else
    fail "mvn package $sample" "build failed — run manually to see errors"
  fi
  popd > /dev/null
done

# ─── Sample 01: dotenv-file ──────────────────────────────────────────────────
section "01-dotenv-file"
SAMPLE_01="$SAMPLES_DIR/01-dotenv-file"
pushd "$SAMPLE_01" > /dev/null
  ENV_FILE="$(pwd)/.env"
  if bella secrets get --app java-01-dotenv-file -o "$ENV_FILE" > /dev/null 2>&1; then
    pass "bella secrets get -o .env"
  else
    fail "bella secrets get -o .env" "command failed"
  fi

  if [[ ! -f "$ENV_FILE" || ! -s "$ENV_FILE" ]]; then
    fail "01: .env file created" ".env missing or empty — bella secrets get may have failed silently"
  else
    OUTPUT="$("$JAVA" -jar target/sample-01-dotenv-file-1.0.0.jar 2>&1)"
    check_all_secrets "01" "$OUTPUT"
  fi

  rm -f "$ENV_FILE"
popd > /dev/null

# ─── Sample 02: process-inject ───────────────────────────────────────────────
section "02-process-inject"
SAMPLE_02="$SAMPLES_DIR/02-process-inject"
pushd "$SAMPLE_02" > /dev/null
  OUTPUT="$(bella run --app java-02-process-inject -- "$JAVA" -jar target/sample-02-process-inject-1.0.0.jar 2>&1)"
  check_all_secrets "02" "$OUTPUT"
popd > /dev/null

# ─── Sample 03: Spring Boot server ───────────────────────────────────────────
section "03-spring-boot"
SAMPLE_03="$SAMPLES_DIR/03-spring-boot"
cleanup_port "$SERVER_PORT_03"

SERVER_03_PID=""
pushd "$SAMPLE_03" > /dev/null
  # Use SERVER_PORT env var (not -Dserver.port) — bella exec stops parsing at --
  # but still passes -D as a JVM flag to the subprocess, which bella misinterprets.
  SERVER_PORT="$SERVER_PORT_03" bella exec --app java-03-spring-boot -- \
    "$JAVA" -jar target/sample-03-spring-boot-1.0.0.jar \
    > /tmp/bella-java-03.log 2>&1 &
  SERVER_03_PID=$!

  if wait_for_server "http://localhost:$SERVER_PORT_03/health" "$SERVER_STARTUP_TIMEOUT"; then
    pass "03-spring-boot: server started"

    RESPONSE="$(curl -sf "http://localhost:$SERVER_PORT_03/" 2>&1)"
    check_server_secrets "03-spring-boot /" "$RESPONSE"

    TYPED_RESPONSE="$(curl -sf "http://localhost:$SERVER_PORT_03/typed" 2>&1)"
    check_server_secrets "03-spring-boot /typed" "$TYPED_RESPONSE"
  else
    fail "03-spring-boot: server started" "did not respond within ${SERVER_STARTUP_TIMEOUT}s"
    cat /tmp/bella-java-03.log
  fi

  kill "$SERVER_03_PID" 2>/dev/null || true
  cleanup_port "$SERVER_PORT_03"
popd > /dev/null
rm -f /tmp/bella-java-03.log

# ─── Sample 04: Quarkus server ───────────────────────────────────────────────
section "04-quarkus"
SAMPLE_04="$SAMPLES_DIR/04-quarkus"
cleanup_port "$SERVER_PORT_04"

SERVER_04_PID=""
pushd "$SAMPLE_04" > /dev/null
  QUARKUS_HTTP_PORT="$SERVER_PORT_04" bella exec --app java-04-quarkus -- \
    "$JAVA" -jar target/quarkus-app/quarkus-run.jar \
    > /tmp/bella-java-04.log 2>&1 &
  SERVER_04_PID=$!

  if wait_for_server "http://localhost:$SERVER_PORT_04/health" "$SERVER_STARTUP_TIMEOUT"; then
    pass "04-quarkus: server started"

    RESPONSE="$(curl -sf "http://localhost:$SERVER_PORT_04/" 2>&1)"
    check_server_secrets "04-quarkus /" "$RESPONSE"

    TYPED_RESPONSE="$(curl -sf "http://localhost:$SERVER_PORT_04/typed" 2>&1)"
    check_server_secrets "04-quarkus /typed" "$TYPED_RESPONSE"
  else
    fail "04-quarkus: server started" "did not respond within ${SERVER_STARTUP_TIMEOUT}s"
    cat /tmp/bella-java-04.log
  fi

  kill "$SERVER_04_PID" 2>/dev/null || true
  cleanup_port "$SERVER_PORT_04"
popd > /dev/null
rm -f /tmp/bella-java-04.log

# ─── Summary ─────────────────────────────────────────────────────────────────
summary_and_exit
