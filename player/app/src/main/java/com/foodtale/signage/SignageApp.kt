package com.foodtale.signage

import android.app.Application

class SignageApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        if (BuildConfig.START_CMS) {
            CmsHost.start(this)
        }
    }
}
