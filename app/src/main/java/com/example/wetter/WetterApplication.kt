package com.example.wetter

import android.app.Application
import android.content.pm.ApplicationInfo

class WetterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Earliest app-code marker on the cold-start timeline (debug builds only).
        val debug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        StartupTrace.log(debug, "WetterApplication.onCreate")
        // Initialize app-wide singletons here
    }
}
