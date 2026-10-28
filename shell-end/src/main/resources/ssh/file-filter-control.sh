# Separate exec channel: request is data and controls only a verified owned native group.
IFS= read -r pid && IFS= read -r ticks && IFS= read -r nonce && IFS= read -r action && IFS= read -r child || exit 1
[[ $pid =~ ^[0-9]+$ && $ticks =~ ^[0-9]+$ && $nonce =~ ^[a-f0-9]{32}$ && $child =~ ^[0-9]+$ ]] || exit 1
identity() {
    [[ -r /proc/$pid/stat && $(stat -c %u /proc/"$pid") == $(id -u) ]] || return 1
    raw=$(< /proc/"$pid"/stat); read -ra f <<< "${raw##*) }"
    [[ ${f[19]} == "$ticks" && ${f[2]} == "$pid" && ${f[3]} == "$pid" ]] || return 1
    tr '\0' '\n' < /proc/"$pid"/environ | grep -F -x -e "WEB_SHELL_FILTER_NONCE=$nonce" >/dev/null
}
identity || exit 2
case $action in
CHECK)
    encoded=
    while IFS= read -r -d '' entry; do case $entry in path64=*) encoded=${entry#path64=} ;; esac; done < /proc/"$pid"/environ
    [[ -n $encoded ]] || exit 3
    decoded=$(printf '%s' "$encoded" | base64 -d; result=$?; printf x; exit "$result") || exit 3
    path=${decoded%x}
    [[ $(stat -Lc '%d:%i' "$path") == $(stat -Lc '%d:%i' /proc/"$pid"/fd/3) ]] || exit 3
    ;;
PROBE)
    raw=$(< /proc/"$child"/stat) || exit 3; read -ra c <<< "${raw##*) }"; [[ ${c[2]} == "$pid" ]] || exit 3
    kill -STOP -- -"$pid" || exit 3
    good=0
    for ((i=0;i<100;i++)); do
        raw=$(< /proc/"$pid"/stat); read -ra p <<< "${raw##*) }"
        raw=$(< /proc/"$child"/stat); read -ra c <<< "${raw##*) }"
        [[ ${p[0]} == T && ${c[0]} == T ]] && { good=1; break; }; sleep .01
    done
    identity && kill -CONT -- -"$pid" || exit 3
    [[ $good == 1 ]] || exit 3
    ;;
STOP|CONT) identity && kill -"$action" -- -"$pid" || exit 3 ;;
CLOSE)
    identity && kill -CONT -- -"$pid" || exit 0
    identity && kill -TERM -- -"$pid" || exit 0
    for ((i=0;i<100;i++)); do identity || { printf 'OK\n'; exit 0; }; sleep .02; done
    identity && kill -KILL -- -"$pid"
    ;;
*) exit 1 ;;
esac
printf 'OK\n'
