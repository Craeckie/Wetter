package com.example.wetter

import android.os.Process
import android.os.SystemClock
import android.util.Log

// Debug-only cold-start instrumentation. Each marker is logged under the "Wetter" logcat tag
// (the same tag the page-console forwarding uses) with a "[startup +<ms>]" prefix, where <ms>
// is measured from the process fork (Process.getStartElapsedRealtime) so a whole capture reads
// as one timeline no matter which callback emitted the line. Gated on the caller's debuggable
// flag, so release builds log nothing at all.
//
// Collect with scripts/capture-startup.sh; isolate the timeline with:
//   adb logcat -s Wetter | grep '\[startup'
// The headline line is "read darkreader.js: N chars in Mms on main thread" -- that M is the
// night-mode-only cost the Dark Reader removal (docs/startup-performance.md, Plan B) targets.
object StartupTrace {
    private val processStart = Process.getStartElapsedRealtime()

    fun log(enabled: Boolean, msg: String) {
        if (!enabled) return
        val t = SystemClock.elapsedRealtime() - processStart
        Log.d("Wetter", "[startup +${t}ms] $msg")
    }
}
