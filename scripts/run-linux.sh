#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [[ -f "$SCRIPT_DIR/doumi-shell.jar" ]]; then ROOT="$SCRIPT_DIR"; else ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"; fi
cd "$ROOT"
JAR="$ROOT/doumi-shell.jar"
if [[ ! -f "$JAR" ]]; then JAR="$ROOT/shell-end/target/shell-end-1.0.0-SNAPSHOT.jar"; fi
if [[ ! -f "$JAR" ]]; then echo 'Build the application first.'; exit 1; fi
export DOUMI_DATA_DIR="${DOUMI_DATA_DIR:-$ROOT/data}"
export DOUMI_PORT="${DOUMI_PORT:-80}"
JAVA_BIN="java"
if [[ -x "$ROOT/runtime/bin/java" ]]; then JAVA_BIN="$ROOT/runtime/bin/java"; fi
echo "Open http://localhost:${DOUMI_PORT}/"
DOUMI_GUACD_MODE="${DOUMI_GUACD_MODE:-external}"
case "$DOUMI_GUACD_MODE" in
    external) exec "$JAVA_BIN" -jar "$JAR" "$@" ;;
    native|docker) ;;
    *) echo 'DOUMI_GUACD_MODE must be external, native or docker.' >&2; exit 1 ;;
esac
# Keep the default SSH-only path independent of the helper, guacd and Docker.
source "$SCRIPT_DIR/guacd-helper.sh"
JAVA_PID=''
SIGNAL_RECEIVED=0
doumi_signal() {
    SIGNAL_RECEIVED=1
    if [[ -n "$JAVA_PID" ]]; then
        kill -"$1" "$JAVA_PID" 2>/dev/null || true
    else
        exit "$2"
    fi
}
trap doumi_guacd_stop EXIT
trap 'doumi_signal TERM 143' TERM
# Async children inherit ignored SIGINT in noninteractive Bash. TERM lets the JVM
# run its shutdown hooks reliably for Ctrl-C too. Keep the JVM's resulting status.
trap 'doumi_signal TERM 130' INT
trap 'doumi_signal HUP 129' HUP
doumi_guacd_start
export DOUMI_LAUNCHER_PID=$$
"$JAVA_BIN" -jar "$JAR" "$@" &
JAVA_PID=$!
set +e
wait "$JAVA_PID"
status=$?
# Bash wait is interrupted by trapped signals; wait again for the actual Java result.
while ((SIGNAL_RECEIVED)); do
    SIGNAL_RECEIVED=0
    wait "$JAVA_PID"
    status=$?
done
exit "$status"
