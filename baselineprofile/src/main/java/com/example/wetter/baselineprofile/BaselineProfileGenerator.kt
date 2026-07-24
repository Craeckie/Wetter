package com.example.wetter.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.example.wetter"

// This generator only matters on a device that can actually produce an ART profile for
// `cmd package dump-profiles` to read. The dev device (Android 13, hardened ROM) can't --
// BaselineProfileRule fails at its extraction step -- which is why the profile that actually
// ships is hand-written (app/src/main/baselineProfiles/baseline-prof.txt). Keep this module
// so a captured profile can replace the hand-written one if a suitable device turns up. See
// docs/startup-performance.md (Plan A.2).
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = PACKAGE,
        maxIterations = 5,
    ) {
        pressHome()
        // Deliberately NOT startActivityAndWait(): it confirms the launch by polling
        // `dumpsys gfxinfo <pkg> framestats`, which on some ROMs returns no frame records
        // at all and aborts the whole run with "Unable to confirm activity launch
        // completion". Profile capture only needs the code to execute -- not frame timings --
        // so starting via the shell and waiting on the UI is equivalent here.
        device.executeShellCommand("am start -W -n $PACKAGE/.MainActivity")
        device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 10_000)
        // wetter's cold start is dominated by the WebView coming up and the weather page
        // loading, which lands seconds after the first frame -- stay long enough for those
        // classes to actually run. See docs/startup-performance.md for the measured timings.
        Thread.sleep(8_000)
    }
}
