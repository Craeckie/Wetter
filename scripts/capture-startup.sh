#!/usr/bin/env bash
# Cold-start capture for the wetter WebView app. Collects `am start -W` timings over N real
# cold starts, screenshots the app at the start and end of the run, gathers device/build
# metadata, and bundles everything into a single zip you can hand straight to Claude Code.
# With --compare-theme it also measures the light-vs-dark launch delta (~the cost of the
# night-mode darkreader.js main-thread path; see docs/startup-performance.md, Plan B).
#
# IMPORTANT: a cold start only loads the heavy weather page if a city is already selected in
# the app. With no city (e.g. right after an uninstall, which wipes app data) it loads the
# light search homepage and the numbers mean something else. The bundle's meta.txt flags which
# page was actually loaded -- check it says WEATHER before trusting a comparison.
#
# Usage:
#   scripts/capture-startup.sh                  # 5 cold launches, current theme -> a zip
#   scripts/capture-startup.sh -n 8 -d 10       # 8 launches, 10s dwell each
#   scripts/capture-startup.sh --theme dark     # force dark for the run
#   scripts/capture-startup.sh --compare-theme  # N in light, N in dark, print the delta
#   scripts/capture-startup.sh -o mylog.log     # choose the logcat output file
#   scripts/capture-startup.sh --no-bundle      # skip the zip (loose files only)
#
# Each launch is force-stopped first so the next start is a genuine cold start (fresh zygote
# fork). The full logcat buffer is dumped once at the end.
set -euo pipefail

PACKAGE=com.example.wetter
ACTIVITY="$PACKAGE/.MainActivity"

LAUNCHES=5
DWELL=8
OUT=startup.log
FORCE_THEME=""      # "", light, or dark
COMPARE_THEME=0
BUNDLE=1

while [ $# -gt 0 ]; do
    case "$1" in
        -n|--launches) LAUNCHES="$2"; shift 2 ;;
        -d|--dwell)    DWELL="$2"; shift 2 ;;
        -o|--out)      OUT="$2"; shift 2 ;;
        --theme)       FORCE_THEME="$2"; shift 2 ;;
        --compare-theme) COMPARE_THEME=1; shift ;;
        --no-bundle)   BUNDLE=0; shift ;;
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

# Everything we'll bundle is staged here; the trap zips from it, then removes it.
WORK=$(mktemp -d)
SUMMARY="$WORK/summary.txt"
: > "$SUMMARY"

# Print to stdout AND capture into the bundle's summary.txt.
say() { printf '%s\n' "$*"; printf '%s\n' "$*" >> "$SUMMARY"; }

# `adb exec-out` avoids the CRLF mangling `adb shell screencap` can do to a PNG.
screenshot() { adb exec-out screencap -p > "$1" 2>/dev/null || echo "WARNING: screencap failed ($1)" >&2; }

# Cold-load the app and screenshot it -- a documentation shot, not a measured launch.
load_and_shot() {
    adb shell am force-stop "$PACKAGE"; sleep 2
    adb shell am start -n "$ACTIVITY" >/dev/null 2>&1 || true
    sleep "$DWELL"
    screenshot "$1"
}

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
PREV_NIGHT=$(adb shell cmd uimode night 2>/dev/null | tr -d '\r' | awk '{print $NF}' || true)

restore_state() {
    [ -n "${PREV_TIMEOUT:-}" ] && [ "$PREV_TIMEOUT" != "null" ] &&
        adb shell settings put system screen_off_timeout "$PREV_TIMEOUT" >/dev/null 2>&1 || true
    if [ -n "${PREV_NIGHT:-}" ] && [ "$PREV_NIGHT" != "null" ]; then
        adb shell cmd uimode night "$PREV_NIGHT" >/dev/null 2>&1 || true
    fi
}
trap 'restore_state; rm -rf "$WORK"' EXIT

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
    # Absorb one throwaway cold launch: `cmd uimode` can restart the app in the background,
    # which otherwise surfaces as an anomalous first measured launch.
    adb shell am force-stop "$PACKAGE"
    sleep 2
    adb shell am start -n "$ACTIVITY" >/dev/null 2>&1 || true
    sleep 3
    adb shell am force-stop "$PACKAGE"
    sleep 2
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
run_condition() {
    local label="$1" ttfile="$2" i start_out tt wt
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
        say "  $label launch $i/$LAUNCHES: TotalTime=${tt:-?}ms WaitTime=${wt:-?}ms"
        [ -n "${tt:-}" ] && printf '%s\n' "$tt" >> "$ttfile"
        sleep "$DWELL"
    done
}

adb logcat -G 16M >/dev/null 2>&1 || echo "note: could not grow log buffer" >&2
# -b main,events: the events buffer carries am_activity_launch_time and the Displayed record.
adb logcat -b main,events -c || true

# "Before" screenshot: a cold-loaded shot of the app at the start of the run, so the bundle
# shows exactly which page was measured (weather vs search).
[ "$COMPARE_THEME" -eq 1 ] && set_theme light
[ "$COMPARE_THEME" -eq 0 ] && [ -n "$FORCE_THEME" ] && set_theme "$FORCE_THEME"
say "== capturing 'before' screenshot (cold load) =="
load_and_shot "$WORK/screenshot-before.png"

if [ "$COMPARE_THEME" -eq 1 ]; then
    say "== compare-theme: $LAUNCHES cold launches each, light then dark =="
    run_condition "light" "$WORK/light.tt"     # theme already set to light above
    set_theme dark
    run_condition "dark" "$WORK/dark.tt"

    LMED=$(median "$WORK/light.tt")
    DMED=$(median "$WORK/dark.tt")
    say ""
    say "---- median cold-start TotalTime ----"
    say "  light: ${LMED}ms"
    say "  dark : ${DMED}ms"
    if [ "$LMED" != "n/a" ] && [ "$DMED" != "n/a" ]; then
        say "  delta (dark - light): $((DMED - LMED))ms  <- ~cost of the night-mode darkreader.js path"
    fi
    say "-------------------------------------"
else
    LABEL="${FORCE_THEME:-system}"
    say "== $LAUNCHES cold launches, theme=$LABEL =="
    run_condition "$LABEL" "$WORK/run.tt"
    say ""
    say "median cold-start TotalTime: $(median "$WORK/run.tt")ms"
fi

# "After" screenshot: the app is still on screen from the last measured launch's dwell.
screenshot "$WORK/screenshot-after.png"

adb shell am force-stop "$PACKAGE"
sleep 2
adb logcat -b main,events -v threadtime -d > "$OUT"
cp "$OUT" "$WORK/$(basename "$OUT")"

# Startup timeline (debug builds only -- release builds emit no [startup +Nms] markers).
grep -aE '\[startup \+' "$OUT" > "$WORK/timeline.txt" 2>/dev/null || true

# analyze_log.py report, if python3 is available (Termux may not have it).
if command -v python3 >/dev/null 2>&1 && [ -f "$(dirname "$0")/analyze_log.py" ]; then
    python3 "$(dirname "$0")/analyze_log.py" "$OUT" > "$WORK/analysis.txt" 2>&1 ||
        echo "(analyze_log.py failed -- see startup.log)" > "$WORK/analysis.txt"
fi

# Metadata, incl. the search-vs-weather check that motivated this whole rewrite.
{
    echo "=== capture-startup meta ==="
    echo "date:     $(date 2>/dev/null || true)"
    echo "launches: $LAUNCHES   dwell: ${DWELL}s"
    echo "mode:     $([ "$COMPARE_THEME" -eq 1 ] && echo compare-theme || echo "${FORCE_THEME:-system}")"
    echo
    echo "--- device ---"
    for p in ro.product.manufacturer ro.product.model ro.build.version.release \
             ro.build.version.security_patch ro.build.fingerprint; do
        echo "$p = $(adb shell getprop "$p" 2>/dev/null | tr -d '\r')"
    done
    echo
    echo "--- app ($PACKAGE) ---"
    adb shell dumpsys package "$PACKAGE" 2>/dev/null | grep -E 'versionName=|versionCode=' | head -2 | sed 's/^[[:space:]]*//'
    echo
    echo "--- dexopt (speed-profile = baseline profile AOT-applied; verify = interpreted) ---"
    adb shell dumpsys package dexopt 2>/dev/null | grep -A1 "$PACKAGE" | head -6
    echo
    echo "--- page actually measured (weather = /de/wetter/<id>; search homepage = /de/) ---"
    if grep -aq 'onPageFinished (weather)' "$OUT"; then
        echo "WEATHER page  (good -- StartupTrace logged onPageFinished (weather))"
    elif grep -aq 'onPageFinished (search)' "$OUT"; then
        echo "SEARCH page  (StartupTrace logged onPageFinished (search) -- pick a city, then re-run!)"
    elif grep -aqE 'kachelmannwetter\.com/de/wetter/[0-9]' "$OUT"; then
        echo "WEATHER page  (good -- a /de/wetter/<id> URL is present in the log)"
    elif grep -aqE 'kachelmannwetter\.com/de' "$OUT"; then
        echo "SEARCH page  (only /de homepage URLs -- pick a city in the app, then re-run!)"
    else
        echo "unknown  (no page signal found in the log)"
    fi
} > "$WORK/meta.txt"

LINES=$(wc -l < "$OUT")
say ""
say "wrote $OUT ($LINES lines)"
[ "$LINES" -lt 500 ] && echo "WARNING: suspiciously short -- check 'adb logcat -d | head'" >&2
echo
echo "---- meta.txt ----"
cat "$WORK/meta.txt"

if [ "$BUNDLE" -eq 1 ]; then
    STAMP=$(date +%Y%m%d-%H%M%S 2>/dev/null || echo run)
    ZIP="startup-results-$STAMP.zip"
    ZPATH="$PWD/$ZIP"
    if command -v zip >/dev/null 2>&1; then
        (cd "$WORK" && zip -q -r "$ZPATH" .)
    else
        ZIP="startup-results-$STAMP.tar.gz"; ZPATH="$PWD/$ZIP"
        tar -czf "$ZPATH" -C "$WORK" .
        echo "note: 'zip' not found -- wrote a .tar.gz instead (pkg install zip for a .zip)" >&2
    fi
    echo
    echo "bundled results -> $ZPATH"
    echo "  screenshot-before.png, screenshot-after.png, $(basename "$OUT"), summary.txt, timeline.txt, meta.txt$([ -f "$WORK/analysis.txt" ] && echo ', analysis.txt')"
    echo "Send that one file to Claude Code."
fi
