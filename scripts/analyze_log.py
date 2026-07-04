#!/usr/bin/env python3
"""Scan a logcat/bug-report text dump for signs of common WebView problems.

Usage:
    scripts/analyze_log.py path/to/log.txt

Takes the kind of logcat export the in-app bug-report tool produces (the
"type: logcat" text files this project has been debugged with). Stdlib only,
no dependencies.

Looks for:
  - __wetter_scroll_watch__ snapshots (see SCROLL_WATCH_JS in MainActivity.kt)
    and flags transitions into a non-scrollable state.
  - Chromium console warnings/errors.
  - Consent-management / CMP script activity (SourcePoint and similar), which
    is the known cause of the scroll-lock bug this script was written for.
  - App crashes/ANRs.
  - Enabled accessibility services, flagged if they could intercept touch
    input (touch exploration or gesture performing).
  - Which WebView engine/version rendered the page.
"""

import argparse
import json
import re
import sys

CONSOLE_RE = re.compile(
    r"^(?P<ts>\S+ \S+)\s+\d+\s+\d+\s+[IWE]\s+chromium:\s+\[(?P<level>INFO|WARNING|ERROR):CONSOLE:\d+\]\s+"
    r'"(?P<message>.*)",\s+source:\s*(?P<source>\S*)\s*\(\d+\)$'
)
CRASH_RE = re.compile(r"FATAL EXCEPTION|Process:\s+com\.example\.wetter.*has died|ANR in")
A11Y_STATE_RE = re.compile(r"New AccessibilityState:\s+State\{(?P<fields>.*)\}")
A11Y_LIST_RE = re.compile(r"Enabled accessibility services list updated\.\s+\[(?P<services>.*)\]")
WEBVIEW_VERSION_RE = re.compile(r"WebViewFactory:\s+Loading\s+(?P<pkg>\S+)\s+version\s+(?P<version>\S+)")

SCROLL_WATCH_MARKER = "__wetter_scroll_watch__"
SCROLL_DIAG_MARKER = "__wetter_scroll_diag__"  # older, one-shot instrumentation format
UNLOCK_MARKER = "__wetter_unlock__"  # emitted by UNLOCK_SCROLL_JS on an actual reassert

CMP_KEYWORDS = (
    "sourcepoint",
    "sppm",
    "onspp",
    "wrappermessaging",
    "cmp",
    "consent",
    "privacy-manager",
    "privacymanager",
)

RISKY_A11Y_FIELDS = ("isTouchExplorationEnabled", "isPerformGesturesEnabled")


def parse_a11y_fields(fields_str):
    fields = {}
    for part in fields_str.split(", "):
        if "=" not in part:
            continue
        key, _, value = part.partition("=")
        fields[key.strip()] = value.strip()
    return fields


def analyze(lines):
    console_events = []
    scroll_watch_events = []
    scroll_diag_events = []
    unlock_events = []
    crashes = []
    a11y_snapshots = []
    webview_versions = []

    for line in lines:
        m = CONSOLE_RE.match(line)
        if m:
            console_events.append(m.groupdict())
            continue
        if CRASH_RE.search(line):
            crashes.append(line.rstrip("\n"))
            continue
        m = A11Y_STATE_RE.search(line)
        if m:
            a11y_snapshots.append(parse_a11y_fields(m.group("fields")))
        m = WEBVIEW_VERSION_RE.search(line)
        if m:
            webview_versions.append((m.group("pkg"), m.group("version")))

    for ev in console_events:
        msg = ev["message"]
        if SCROLL_WATCH_MARKER in msg:
            payload = msg.split(SCROLL_WATCH_MARKER, 1)[1].strip()
            try:
                scroll_watch_events.append((ev["ts"], json.loads(payload)))
            except json.JSONDecodeError:
                pass
        elif SCROLL_DIAG_MARKER in msg:
            payload = msg.split(SCROLL_DIAG_MARKER, 1)[1].strip()
            try:
                scroll_diag_events.append((ev["ts"], json.loads(payload)))
            except json.JSONDecodeError:
                pass
        elif UNLOCK_MARKER in msg:
            payload = msg.split(UNLOCK_MARKER, 1)[1].strip()
            try:
                unlock_events.append((ev["ts"], json.loads(payload)))
            except json.JSONDecodeError:
                pass

    return {
        "console_events": console_events,
        "scroll_watch_events": scroll_watch_events,
        "scroll_diag_events": scroll_diag_events,
        "unlock_events": unlock_events,
        "crashes": crashes,
        "a11y_snapshots": a11y_snapshots,
        "webview_versions": webview_versions,
    }


def is_cmp_related(message, source):
    haystack = (message + " " + source).lower()
    return any(keyword in haystack for keyword in CMP_KEYWORDS)


def report(result):
    lines_out = []

    def emit(text=""):
        lines_out.append(text)

    warnings_errors = [
        ev for ev in result["console_events"] if ev["level"] in ("WARNING", "ERROR")
    ]
    cmp_events = [
        ev for ev in result["console_events"] if is_cmp_related(ev["message"], ev["source"])
    ]

    emit("== WebView engine ==")
    if result["webview_versions"]:
        pkg, version = result["webview_versions"][0]
        emit(f"{pkg} {version}")
    else:
        emit("(not found in log)")
    emit()

    emit("== Consent-management / CMP activity ==")
    if cmp_events:
        for ev in cmp_events:
            emit(f"{ev['ts']}  {ev['message']}")
        emit(
            "-> Known cause of the scroll-lock bug: these scripts set "
            "`body {position:fixed; overflow:hidden}` while showing a consent "
            "dialog and rely on the dialog to release it. If scrolling froze "
            "shortly after these lines, check the scroll-watch events below."
        )
    else:
        emit("(none detected)")
    emit()

    scroll_events = result["scroll_watch_events"] or result["scroll_diag_events"]
    emit("== Scroll state timeline ==")
    if scroll_events:
        prev_scrollable = None
        for ts, snap in scroll_events:
            scrollable = snap.get("scrollable")
            if scrollable is None:
                # older one-shot diagnostic format didn't compute this directly
                scrollable = snap.get("htmlScrollHeight", 0) > snap.get("innerHeight", 0) + 1
            flag = ""
            if prev_scrollable is True and scrollable is False:
                flag = "  <-- SCROLL LOCK ENGAGED HERE"
            elif prev_scrollable is False and scrollable is True:
                flag = "  <-- scroll lock released"
            prev_scrollable = scrollable
            emit(f"{ts}  scrollable={scrollable}  {json.dumps(snap)}{flag}")
        if prev_scrollable is False:
            emit(
                "-> Page ended non-scrollable. If a CMP event above happened just "
                "before, that script's scroll-lock is the likely cause; the fix "
                "pattern used in this app is an inline !important override on body "
                "(see UNLOCK_SCROLL_JS in MainActivity.kt)."
            )
    else:
        emit(
            "(none found -- this log predates the scroll-watch instrumentation, "
            "or the debug build wasn't used)"
        )
    emit()

    emit("== Scroll unlock reasserts ==")
    unlock_events = result["unlock_events"]
    if unlock_events:
        emit(
            "UNLOCK_SCROLL_JS reasserted scroll (each line = the consent lock being "
            "cleared; 't' is ms since page load):"
        )
        for ts, snap in unlock_events:
            emit(f"{ts}  was={snap.get('was')}  t={snap.get('t')}ms")
        emit(
            "-> These confirm the unlock fired. With the rAF safety net the lock should "
            "clear within one frame of engaging; a long gap after a CMP event above "
            "means the reassert is lagging."
        )
    else:
        emit(
            "(none -- either the page never locked, this is a release build "
            "(breadcrumb is debug-only), or the log predates this instrumentation)"
        )
    emit()

    emit("== Other chromium console warnings/errors ==")
    other = [ev for ev in warnings_errors if ev not in cmp_events]
    if other:
        for ev in other[:30]:
            emit(f"{ev['ts']}  [{ev['level']}] {ev['message']}")
        if len(other) > 30:
            emit(f"... and {len(other) - 30} more")
    else:
        emit("(none)")
    emit()

    emit("== Crashes / ANRs ==")
    if result["crashes"]:
        for c in result["crashes"]:
            emit(c)
    else:
        emit("(none)")
    emit()

    emit("== Accessibility services ==")
    if result["a11y_snapshots"]:
        latest = result["a11y_snapshots"][-1]
        for field in RISKY_A11Y_FIELDS:
            value = latest.get(field)
            if value == "true":
                emit(f"WARNING: {field}=true -- could intercept touch/scroll gestures")
        emit(f"latest state: {latest}")
    else:
        emit("(none reported)")

    return "\n".join(lines_out)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("log_file", help="Path to a logcat/bug-report text export")
    args = parser.parse_args()

    with open(args.log_file, encoding="utf-8", errors="replace") as f:
        lines = f.readlines()

    result = analyze(lines)
    print(report(result))


if __name__ == "__main__":
    main()
