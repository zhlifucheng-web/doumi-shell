# Constant program. All request fields arrive as data records, never shell source.
set -o pipefail
unsupported() { printf 'UNSUPPORTED\t%s\n' "$1"; exit 0; }
if [[ ${WEB_SHELL_NATIVE_GROUP:-} != 1 ]]; then
    IFS= read -r path64 && IFS= read -r keyword64 && IFS= read -r size && IFS= read -r context && IFS= read -r prefix64 && IFS= read -r boundary64 && IFS= read -r seed64 && IFS= read -r WEB_SHELL_FILTER_NONCE || exit 1
    [[ $size =~ ^[0-9]{1,18}$ && $context =~ ^(0|2)$ && $WEB_SHELL_FILTER_NONCE =~ ^[a-f0-9]{32}$ ]] || unsupported parameters
    for tool in grep head tail wc iconv stat setsid base64 tr sleep id; do command -v "$tool" >/dev/null 2>&1 || unsupported "missing-$tool"; done
    export WEB_SHELL_NATIVE_GROUP=1 WEB_SHELL_FILTER_NONCE path64 keyword64 size context prefix64 boundary64 seed64
    # Older util-linux/BusyBox setsid lack -w. Keep an outer waiter and explicit stdin.
    setsid bash -c "$BASH_EXECUTION_STRING" <&0 & group_launcher=$!
    wait "$group_launcher"
    exit "$?"
fi
[[ $BASHPID == $$ ]] || unsupported group
raw=$(< /proc/$$/stat) || unsupported proc
read -ra fields <<< "${raw##*) }"
[[ ${fields[2]} == $$ && ${fields[3]} == $$ ]] || unsupported group
start=${fields[19]}
ulimit -v 262144 2>/dev/null || unsupported memory-limit
ulimit -t 120 2>/dev/null || unsupported cpu-limit
ulimit -c 0 2>/dev/null || unsupported core-limit
cleanup() { trap - EXIT HUP INT; trap '' TERM; kill -TERM -- -$$ 2>/dev/null; wait; }
trap cleanup EXIT HUP INT TERM
# Exact behavior probes; versions alone do not establish compatibility.
[[ $(printf 'x\nhit\ny\n' | LC_ALL=C grep -a -F -n -b -C 2 -e hit) == $'1-0-x\n2:2:hit\n3-6-y' ]] || unsupported grep-flags
[[ $(printf '.*\n-needle\n' | LC_ALL=C grep -a -F -n -b -e '.*') == '1:0:.*' ]] || unsupported grep-literal
printf x | grep -F -e missing >/dev/null; [[ $? == 1 ]] || unsupported grep-status
[[ $(printf abc | head -c 2) == ab && $(printf abc | tail -c +2) == bc && $(printf 'a\nb\nc\n' | tail -n 2) == $'b\nc' ]] || unsupported head-tail
[[ $(printf 'a\nb\n' | wc -l) =~ ^[[:space:]]*2[[:space:]]*$ ]] || unsupported wc
printf '\377' | iconv -f UTF-8 -t UTF-8 >/dev/null 2>&1; [[ $? != 0 ]] || unsupported iconv-strict
printf '\344\270\255' | iconv -f UTF-8 -t UTF-8 >/dev/null 2>&1 || unsupported iconv-utf8
[[ $(printf eA== | base64 -d 2>/dev/null) == x ]] || unsupported base64
# Sentinel preserves trailing newlines in file names; validate decoder status separately.
decoded=$(printf '%s' "$path64" | base64 -d; result=$?; printf x; exit "$result") || unsupported parameters
path=${decoded%x}
decoded=$(printf '%s' "$keyword64" | base64 -d; result=$?; printf x; exit "$result") || unsupported parameters
keyword=${decoded%x}
[[ -f $path ]] || unsupported regular-file
exec 3< "$path" || unsupported open
fdpath=/proc/$$/fd/3
identity=$(stat -Lc '%d:%i' "$fdpath") || unsupported stat
[[ $identity =~ ^[0-9]+:[0-9]+$ ]] || unsupported stat-format
slice() { head -c "$size" "$fdpath"; }
anchor() { tail -c "+$1" "$fdpath" | head -c "$2" | base64 | tr -d '\n'; }
check() {
    [[ $(stat -Lc '%d:%i' "$path" 2>/dev/null) == "$identity" ]] || return 1
    current=$(stat -Lc '%s' "$fdpath") || return 1
    [[ $current =~ ^[0-9]+$ && $current -ge $size ]] || return 1
    # Ignore SIGPIPE from a concurrently growing tail; compare exact bounded anchor bytes.
    p=$(head -c "$((size < 4096 ? size : 4096))" "$fdpath" | base64 | tr -d '\n') || return 1
    b=$(anchor "$((size > 4096 ? size - 4096 + 1 : 1))" "$((size < 4096 ? size : 4096))")
    [[ $p == "$prefix64" && $b == "$boundary64" ]]
}
check || unsupported file-changed
seed_length=$(printf '%s' "$seed64" | base64 -d | wc -c)
actual_seed=$(anchor "$((size - seed_length + 1))" "$seed_length")
[[ $actual_seed == "$seed64" ]] || unsupported file-changed
if ((size > 0)); then [[ $(anchor "$size" 1) == Cg== ]] || unsupported partial-eof; fi
sleep 60 & probe_child=$!
printf 'READY\t%s\t%s\t%s\n' "$$" "$start" "$probe_child"
IFS= read -r -t 15 command || exit 0
[[ $command == go ]] || exit 0
kill "$probe_child" 2>/dev/null; wait "$probe_child" 2>/dev/null
# Validation/count pass and grep pass both read only the captured historical prefix.
count=$(slice | iconv -f UTF-8 -t UTF-8 2>/dev/null | wc -l) || { printf 'END\tENCODING\n'; exit 0; }
count=${count//[[:space:]]/}
printf 'COUNT\t%s\n' "$count"
# grep exit 1 means a successful scan with no matches. Preserve head failures.
if ((context == 2)); then slice | LC_ALL=C grep -a -F -n -b -C 2 -e "$keyword"; else slice | LC_ALL=C grep -a -F -n -b -e "$keyword"; fi
statuses=("${PIPESTATUS[@]}")
if [[ ${statuses[0]} != 0 || (${statuses[1]} != 0 && ${statuses[1]} != 1) ]]; then printf 'END\tFAILED\n'; exit 0; fi
if check; then printf 'END\tOK\n'; else printf 'END\tCHANGED\n'; fi
# Keep the original FD and leader alive until the Java handoff has verified its anchors.
while IFS= read -r -t 600 command; do [[ $command == close ]] && break; done
