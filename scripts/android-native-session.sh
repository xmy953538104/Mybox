#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# A private session for one standalone test. Never source this in an app process.
set -eu
mode=${1:?mode}; directory=${2:?directory}; cookie=${3:?cookie}; expected_uid=${4:?uid}
case "$directory" in /data/user/0/io.github.xmy953538104.mybox/cache/mylite-native-*/round-[123]) ;; *) exit 125 ;; esac
case "$cookie" in ''|*[!a-f0-9]*) exit 125 ;; esac
case "$expected_uid" in ''|*[!0-9]*) exit 125 ;; esac
[ "$(id -u)" = "$expected_uid" ] && [ "$expected_uid" -ge 10000 ] || exit 125

# Read field 22 without assuming comm has no spaces or parentheses.
read_identity() {
    IFS= read -r process_stat < "/proc/$1/stat" || return 1
    process_fields=${process_stat##*) }
    set -- $process_fields
    identity_group=$3; identity_session=$4
    shift 19
    identity_start=$1
}

if [ "$mode" = cleanup ]; then
    # The marker is written before any test/watchdog fork and never reused.
    [ -f "$directory/identity" ] || { echo 'CLEANUP no identity; no test was admitted'; exit 1; }
    IFS=' ' read -r recorded_cookie leader recorded_start recorded_uid < "$directory/identity"
    [ "$recorded_cookie" = "$cookie" ] && [ "$recorded_uid" = "$expected_uid" ] || exit 125
    case "$leader:$recorded_start" in *[!0-9:]*|:*) exit 125 ;; esac
    [ "$leader" -gt 1 ] || exit 125
    if [ -d "/proc/$leader" ]; then
        read_identity "$leader" || { echo 'CLEANUP cannot read leader'; exit 1; }
        [ "$identity_group" = "$leader" ] && [ "$identity_session" = "$leader" ] \
            && [ "$identity_start" = "$recorded_start" ] || { echo 'CLEANUP identity changed; refusing signal'; exit 1; }
        actual_uid=$(awk '/^Uid:/ {print $2}' "/proc/$leader/status")
        [ "$actual_uid" = "$expected_uid" ] || { echo 'CLEANUP UID changed; refusing signal'; exit 1; }
        # The verified leader's trap kills its CURRENT group, not a broad UID/name match.
        kill -TERM "$leader" || { echo 'CLEANUP leader signal failed'; exit 1; }
    fi
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        if kill -0 -- "-$leader" 2> "$directory/group-check-error"; then
            sleep 0.1
        else
            # Permission denial is not evidence that the group disappeared.
            if grep -qi 'no such process' "$directory/group-check-error"; then
                echo "CLEANUP group $leader absent"
                exit 0
            fi
            cat "$directory/group-check-error"
            echo 'CLEANUP group existence unavailable'
            exit 1
        fi
    done
    echo "CLEANUP group $leader still present; no unverified group signal sent"
    exit 1
fi

[ "$mode" = run ] || exit 125
deadline=${5:?deadline}
case "$deadline" in ''|*[!0-9]*) exit 125 ;; esac
[ "$deadline" -ge 10 ] && [ "$deadline" -le 120 ] || exit 125
exec > "$directory/session.log" 2>&1
read_identity $$
[ "$identity_group" = "$$" ] && [ "$identity_session" = "$$" ] || { echo 'Not a dedicated session'; exit 125; }
printf '%s %s %s %s\n' "$cookie" "$$" "$identity_start" "$expected_uid" > "$directory/identity"
id
id -Z
cat "/proc/$$/status"
cat "/proc/$$/attr/current"

# Every fork below inherits this session/group. Tests contain no setsid/setpgid calls.
# Both cleanup paths kill group 0 from a living member, so the group ID cannot be reused.
abort_session() {
    printf '%s\n' 'host or transport interrupted session' > "$directory/aborted" || :
    kill -KILL 0
    exit 125
}
trap abort_session TERM INT HUP
export TMPDIR="$directory/tmp" MYLITE_EXPECTED_UID="$expected_uid"
mkdir "$TMPDIR"
ulimit -c 0
(
    sleep "$deadline"
    printf '%s\n' "$deadline" > "$directory/timed-out" || :
    kill -KILL 0
    exit 125
) &
watchdog_pid=$!
if ! read_identity "$watchdog_pid" || [ "$identity_group" != "$$" ] || [ "$identity_session" != "$$" ]; then
    abort_session
fi
"$directory/../native-test" > "$directory/test.log" 2>&1 &
test_pid=$!
printf '%s\n' "$test_pid" > "$directory/test-pid" || abort_session
set +e
wait "$test_pid"
test_status=$?
set -e
printf '%s\n' "$test_status" > "$directory/exit-code" || :
kill -KILL 0
exit 125
