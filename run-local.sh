#!/usr/bin/env bash
#
# Start the Agentic Banking app locally with secrets loaded automatically.
#
#   ./run-local.sh                 # load .env.local (or existing env), then mvn spring-boot:run
#   ./run-local.sh --dry-run       # verify secret loading without starting the app
#   ./run-local.sh -- --server.port=8090   # extra args are passed to spring-boot:run
#
# Secrets live in .env.local (gitignored — see .env.local.example). An already-exported
# NVIDIA_API_KEY always wins over the file, so CI/shell exports keep working.
set -euo pipefail
cd "$(dirname "$0")"

DRY_RUN=0
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=1
  shift
fi
if [[ "${1:-}" == "--" ]]; then
  shift
fi

# Load gitignored local secrets only for variables not already present in the environment.
load_env_file() {
  local file="$1"
  [[ -f "$file" ]] || return 1
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line#"${line%%[![:space:]]*}"}"          # trim leading whitespace
    [[ -z "$line" || "$line" == \#* ]] && continue   # skip blanks/comments
    [[ "$line" == *=* ]] || continue
    local key="${line%%=*}"
    local value="${line#*=}"
    key="${key%"${key##*[![:space:]]}"}"             # trim trailing whitespace on key
    value="${value%\"}"; value="${value#\"}"         # tolerate simple double quotes
    value="${value%\'}"; value="${value#\'}"
    if [[ -z "${!key:-}" ]]; then
      export "$key=$value"
    fi
  done < "$file"
  return 0
}

LOADED_FROM=""
if [[ -z "${NVIDIA_API_KEY:-}" ]]; then
  if load_env_file ".env.local" && [[ -n "${NVIDIA_API_KEY:-}" ]]; then
    LOADED_FROM=".env.local"
  elif load_env_file ".env" && [[ -n "${NVIDIA_API_KEY:-}" ]]; then
    LOADED_FROM=".env"
  fi
else
  LOADED_FROM="shell environment"
fi

if [[ -z "${NVIDIA_API_KEY:-}" || "${NVIDIA_API_KEY}" == nvapi-PASTE-YOUR-KEY-HERE ]]; then
  cat >&2 <<'MSG'
✗ NVIDIA_API_KEY is not set.

  Fix it once (gitignored, never committed):
      cp .env.local.example .env.local
      # edit .env.local and paste your real key from https://build.nvidia.com

  Or export it for this shell instead:
      export NVIDIA_API_KEY="nvapi-..."
MSG
  exit 1
fi

echo "✓ NVIDIA_API_KEY loaded (${#NVIDIA_API_KEY} chars, source: $LOADED_FROM)"

# pom.xml targets Java 25: reuse JAVA_HOME, else find a JDK 25+ (system or IntelliJ-downloaded).
java_major() { "$1/bin/java" -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print v[1]; exit}'; }
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" || "$(java_major "$JAVA_HOME")" -lt 25 ]]; then
  JAVA_HOME=""
  for candidate in "$(/usr/libexec/java_home -v 25+ 2>/dev/null || true)" \
                   "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home \
                   /Library/Java/JavaVirtualMachines/*/Contents/Home \
                   /opt/homebrew/opt/openjdk*/libexec/openjdk.jdk/Contents/Home; do
    if [[ -n "$candidate" && -x "$candidate/bin/java" && "$(java_major "$candidate")" -ge 25 ]]; then
      JAVA_HOME="$candidate"
      break
    fi
  done
  if [[ -z "$JAVA_HOME" ]]; then
    cat >&2 <<'MSG'
✗ No JDK 25+ found. Install one, then re-run:
      brew install --cask temurin        # or: IntelliJ → Project Structure → SDK → Download JDK 25
MSG
    exit 1
  fi
  export JAVA_HOME
fi
echo "✓ Java: $JAVA_HOME"

# Port: explicit --server.port / PORT wins (fails if busy); otherwise auto-pick the first free one from 8080.
port_pid() { lsof -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null | head -1 || true; }
EXPLICIT_PORT="${PORT:-}"
for arg in "$@"; do [[ "$arg" == --server.port=* ]] && EXPLICIT_PORT="${arg#--server.port=}"; done
if [[ -n "$EXPLICIT_PORT" ]]; then
  PORT="$EXPLICIT_PORT"
  if busy_pid=$(port_pid "$PORT") && [[ -n "$busy_pid" ]]; then
    echo "✗ Port $PORT is already in use (PID $busy_pid). Run 'kill $busy_pid' or pick another port." >&2
    exit 1
  fi
else
  PORT=""
  for p in $(seq 8080 8099); do
    if [[ -z "$(port_pid "$p")" ]]; then PORT="$p"; break; fi
  done
  [[ -n "$PORT" ]] || { echo "✗ No free port in 8080-8099." >&2; exit 1; }
  [[ "$PORT" != 8080 ]] && echo "ℹ Port 8080 is busy (PID $(port_pid 8080)) — using $PORT instead."
  set -- "$@" "--server.port=$PORT"
fi
echo "  Workbench:       http://localhost:$PORT/"
echo "  Mission Control: http://localhost:$PORT/mission-control.html"

if [[ "$DRY_RUN" == "1" ]]; then
  echo "✓ Dry run — configuration OK, not starting the app."
  exit 0
fi

if [[ -z "${MVN:-}" ]]; then
  if command -v mvn >/dev/null 2>&1; then MVN=mvn; else MVN=./mvnw; fi
fi
exec "$MVN" spring-boot:run ${@:+-Dspring-boot.run.arguments="$*"}
