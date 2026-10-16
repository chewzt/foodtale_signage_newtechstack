package com.foodtale.signage

import android.app.Application
import android.os.SystemClock

class SignageApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startedElapsed = SystemClock.elapsedRealtime()
        Prefs.init(this)
    }

    companion object {
        @Volatile var startedElapsed: Long = 0L
            private set

        fun uptimeMs(): Long = (SystemClock.elapsedRealtime() - startedElapsed).coerceAtLeast(0L)
    }
}
