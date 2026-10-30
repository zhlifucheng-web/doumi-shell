#!/usr/bin/env bash
# Sourced by run-linux.sh only for explicitly managed gateways.
DOUMI_GUACD_PID=''
DOUMI_GUACD_CONTAINER=''

doumi_guacd_error() { echo "guacd: $*" >&2; }
doumi_guacd_ready() { (exec 3<>"/dev/tcp/${DOUMI_GUACD_HOST}/${DOUMI_GUACD_PORT}") 2>/dev/null; }

doumi_guacd_stop() {
    if [[ -n "$DOUMI_GUACD_PID" ]]; then
        if kill -0 "$DOUMI_GUACD_PID" 2>/dev/null; then
            kill -TERM "$DOUMI_GUACD_PID" 2>/dev/null || true
            local end=$((SECONDS + 5))
            while kill -0 "$DOUMI_GUACD_PID" 2>/dev/null && ((SECONDS < end)); do sleep 0.1; done
            kill -KILL "$DOUMI_GUACD_PID" 2>/dev/null || true
        fi
        wait "$DOUMI_GUACD_PID" 2>/dev/null || true
        DOUMI_GUACD_PID=''
    fi
    if [[ -n "$DOUMI_GUACD_CONTAINER" ]]; then
        docker rm --force "$DOUMI_GUACD_CONTAINER" >/dev/null || true
        DOUMI_GUACD_CONTAINER=''
    fi
}

doumi_guacd_start() {
    export DOUMI_GUACD_HOST="${DOUMI_GUACD_HOST:-127.0.0.1}"
    export DOUMI_GUACD_PORT="${DOUMI_GUACD_PORT:-4822}"
    local timeout="${DOUMI_GUACD_START_TIMEOUT:-15}" end binary
    # One canonical address keeps the managed listener and backend in agreement.
    if [[ "$DOUMI_GUACD_HOST" != '127.0.0.1' ]]; then
        doumi_guacd_error 'managed mode requires DOUMI_GUACD_HOST=127.0.0.1'; return 1
    fi
    if [[ ! "$DOUMI_GUACD_PORT" =~ ^[0-9]{1,5}$ ]] || ((10#$DOUMI_GUACD_PORT < 1 || 10#$DOUMI_GUACD_PORT > 65535)); then
        doumi_guacd_error 'DOUMI_GUACD_PORT must be 1..65535'; return 1
    fi
    if [[ ! "$timeout" =~ ^[0-9]{1,3}$ ]] || ((10#$timeout < 1 || 10#$timeout > 300)); then
        doumi_guacd_error 'DOUMI_GUACD_START_TIMEOUT must be 1..300 seconds'; return 1
    fi
    if doumi_guacd_ready; then
        doumi_guacd_error 'port is already in use; use external mode for an existing gateway'; return 1
    fi
    case "$DOUMI_GUACD_MODE" in
        native)
            binary="${DOUMI_GUACD_BIN:-guacd}"
            if ! command -v "$binary" >/dev/null 2>&1; then
                doumi_guacd_error 'native guacd is not installed; set DOUMI_GUACD_BIN or use external mode'; return 1
            fi
            "$binary" -f -b "$DOUMI_GUACD_HOST" -l "$DOUMI_GUACD_PORT" &
            DOUMI_GUACD_PID=$!
            ;;
        docker)
            if ! command -v docker >/dev/null 2>&1; then
                doumi_guacd_error 'Docker is not installed; install it separately or use external mode'; return 1
            fi
            # Store only the ID successfully created by this invocation. Never remove by a shared name.
            DOUMI_GUACD_CONTAINER="$(docker create --name "doumi-guacd-$$-${RANDOM}-${RANDOM}" \
                --publish "127.0.0.1:${DOUMI_GUACD_PORT}:4822" guacamole/guacd:1.6.0)" || return 1
            if [[ -z "$DOUMI_GUACD_CONTAINER" ]]; then doumi_guacd_error 'Docker returned no container ID'; return 1; fi
            docker start "$DOUMI_GUACD_CONTAINER" >/dev/null || return 1
            ;;
    esac
    SECONDS=0
    end=$((10#$timeout))
    while ((SECONDS < end)); do
        if [[ -n "$DOUMI_GUACD_PID" ]] && ! kill -0 "$DOUMI_GUACD_PID" 2>/dev/null; then
            doumi_guacd_error 'native process exited before becoming ready'; return 1
        fi
        if [[ -n "$DOUMI_GUACD_CONTAINER" ]] && [[ "$(docker inspect --format '{{.State.Running}}' "$DOUMI_GUACD_CONTAINER")" != 'true' ]]; then
            doumi_guacd_error 'container exited before becoming ready'; return 1
        fi
        if doumi_guacd_ready; then return 0; fi
        sleep 0.1
    done
    doumi_guacd_error "readiness timed out after ${timeout}s at ${DOUMI_GUACD_HOST}:${DOUMI_GUACD_PORT}"
    return 1
}
