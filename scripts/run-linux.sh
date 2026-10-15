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
exec "$JAVA_BIN" -jar "$JAR"
