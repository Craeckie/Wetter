#!/usr/bin/env bash
# Cold-start capture for the wetter WebView app: collects `am start -W` timings and a full
# threadtime logcat over N real cold starts, and (with --compare-theme) measures the
# light-vs-dark launch delta -- which is ~the cost of the 340 KB darkreader.js main-thread
# read/parse that only runs in night mode. See docs/startup-performance.md (Plan B).
#
# Usage:
#   scripts/capture-startup.sh                  # 5 cold launches, current system theme
#   scripts/capture-startup.sh -n 8 -d 10       # 8 launches, 10s dwell each
#   scripts/capture-startup.sh --theme dark     # force dark for the run
#   scripts/capture-startup.sh --compare-theme  # N in light, N in dark, print the delta
#   scripts/capture-startup.sh -o mylog.log     # choose the logcat output file
#
# Each launch is force-stopped first so the next start is a genuine cold start (fresh zygote
# fork). The full logcat buffer is dumped once at the end to the output file; open it, or
# grep it for `Displayed`, `Choreographer` (skipped frames), `Davey!` (jank), and GC pauses.
set -euo pipefail

PACKAGE=com.example.wetter
ACTIVITY="$PACKAGE/.MainActivity"

LAUNCHES=5
DWELL=8
OUT=startup.log
FORCE_THEME=""      # "", light, or dark
COMPARE_THEME=0

while [ $# -gt 0 ]; do
    case "$1" in
        -n|--launches) LAUNCHES="$2"; shift 2 ;;
        -d|--dwell)    DWELL="$2"; shift 2 ;;
        -o|--out)      OUT="$2"; shift 2 ;;
        --theme)       FORCE_THEME="$2"; shift 2 ;;
        --compare-theme) COMPARE_THEME=1; shift ;;
        -h|--help)
            grep -E '^#( |$)' "$0" | sed -E 's/^# ?//'
            exit 0 ;;
        *) echo "unknown argument: $1 (try --help)" >&2; exit 1 ;;
    esac
done

command -v adb >/dev/null || { echo "adb not on PATH (apt/pkg install android-tools)" >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "no device -- plug in / 'adb connect' first" >&2; exit 1; }
# Fail up front: `am start` writes "Error: ..." to stdout, which the TotalTime grep would
# otherwise swallow, leaving a log with no launches in it and no visible complaint.
if [ -z "$(adb shell pm path "$PACKAGE" 2>/dev/null | tr -d '\r')" ]; then
    echo "$PACKAGE is not installed -- build & install a debug APK first" >&2
    exit 1
fi

# Screen must be on and unlocked or the activity starts without ever drawing a frame.
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
sleep 1
if adb shell dumpsys window 2>/dev/null | grep -q "mShowingLockscreen=true"; then
    echo "WARNING: still locked -- unlock the phone by hand (adb can't enter a PIN/pattern)" >&2
fi

# Keep the screen awake for the whole run; restore the user's timeout on exit.
PREV_TIMEOUT=$(adb shell settings get system screen_off_timeout 2>/dev/null | tr -d '\r' || true)
adb shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1 || true

# Remember the original system night mode so --theme / --compare-theme can restore it.
# `cmd uimode night` prints e.g. "Night mode: no" / "yes" / "auto".
PREV_NIGHT=$(adb shell cmd uimode night 2>/dev/null | tr -d '\r' | awk '{print $NF}' || true)

restore_state() {
    [ -n "${PREV_TIMEOUT:-}" ] && [ "$PREV_TIMEOUT" != "null" ] &&
        adb shell settings put system screen_off_timeout "$PREV_TIMEOUT" >/dev/null 2>&1 || true
    if [ -n "${PREV_NIGHT:-}" ] && [ "$PREV_NIGHT" != "null" ]; then
        adb shell cmd uimode night "$PREV_NIGHT" >/dev/null 2>&1 || true
    fi
}
trap restore_state EXIT

set_theme() {
    # $1 = light | dark ; applied to the whole system so the next cold start picks it up.
    local mode="$1" arg
    case "$mode" in
        light) arg=no ;;
        dark)  arg=yes ;;
        *) return 0 ;;
    esac
    if ! adb shell cmd uimode night "$arg" >/dev/null 2>&1; then
        echo "WARNING: couldn't set night mode to '$arg' (cmd uimode unsupported?)" >&2
    fi
    sleep 2   # let the system settle after the reconfiguration
}

# median <file-of-numbers> -> integer median (or "n/a" if empty)
median() {
    sort -n "$1" | awk '
        { a[NR]=$1 }
        END {
            if (NR==0) { print "n/a" }
            else if (NR%2) { print a[(NR+1)/2] }
            else { printf "%.0f\n", (a[NR/2]+a[NR/2+1])/2 }
        }'
}

# run_condition <label> <totaltime-outfile>
# Cold-launches LAUNCHES times, printing per-launch TotalTime and appending each value to the
# given file (one integer per line) for the median summary at the end.
run_condition() {
    local label="$1" ttfile="$2" i start_out tt
    : > "$ttfile"
    for i in $(seq 1 "$LAUNCHES"); do
        adb shell am force-stop "$PACKAGE"
        sleep 2
        start_out=$(adb shell am start -W -n "$ACTIVITY" 2>&1)
        if printf '%s' "$start_out" | grep -q "Error"; then
            printf '%s\n' "$start_out" >&2
            echo "launch failed -- aborting" >&2
            exit 1
        fi
        tt=$(printf '%s\n' "$start_out" | grep -oE 'TotalTime: [0-9]+' | grep -oE '[0-9]+' | head -1)
        wt=$(printf '%s\n' "$start_out" | grep -oE 'WaitTime: [0-9]+' | grep -oE '[0-9]+' | head -1)
        printf '  %s launch %d/%d: TotalTime=%sms WaitTime=%sms\n' \
            "$label" "$i" "$LAUNCHES" "${tt:-?}" "${wt:-?}"
        [ -n "${tt:-}" ] && printf '%s\n' "$tt" >> "$ttfile"
        sleep "$DWELL"
    done
}

adb logcat -G 16M >/dev/null 2>&1 || echo "note: could not grow log buffer" >&2
# -b main,events: the events buffer carries am_activity_launch_time and the Displayed record.
adb logcat -b main,events -c || true

TMP=$(mktemp -d)
trap 'restore_state; rm -rf "$TMP"' EXIT

if [ "$COMPARE_THEME" -eq 1 ]; then
    echo "== compare-theme: $LAUNCHES cold launches each, light then dark =="
    set_theme light
    run_condition "light" "$TMP/light.tt"
    set_theme dark
    run_condition "dark" "$TMP/dark.tt"

    LMED=$(median "$TMP/light.tt")
    DMED=$(median "$TMP/dark.tt")
    echo
    echo "---- median cold-start TotalTime ----"
    printf '  light: %sms\n  dark : %sms\n' "$LMED" "$DMED"
    if [ "$LMED" != "n/a" ] && [ "$DMED" != "n/a" ]; then
        printf '  delta (dark - light): %sms  <- ~cost of the night-mode darkreader.js path\n' \
            "$((DMED - LMED))"
    fi
    echo "-------------------------------------"
else
    [ -n "$FORCE_THEME" ] && set_theme "$FORCE_THEME"
    LABEL="${FORCE_THEME:-system}"
    echo "== $LAUNCHES cold launches, theme=$LABEL =="
    run_condition "$LABEL" "$TMP/run.tt"
    echo
    echo "median cold-start TotalTime: $(median "$TMP/run.tt")ms"
fi

adb shell am force-stop "$PACKAGE"
sleep 2
adb logcat -b main,events -v threadtime -d > "$OUT"

LINES=$(wc -l < "$OUT")
echo
echo "wrote $OUT ($LINES lines)"
[ "$LINES" -lt 500 ] && echo "WARNING: suspiciously short -- check 'adb logcat -d | head'" >&2
echo "inspect with:  grep -E 'Displayed|Choreographer|Davey|Skipped' $OUT"
echo "or the analyzer: python3 scripts/analyze_log.py $OUT"
