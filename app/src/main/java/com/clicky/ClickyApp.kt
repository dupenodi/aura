package com.clicky

import android.app.Application
import android.content.Context
import com.clicky.debug.RingBufferLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ClickyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        app = this
        RingBufferLogger.log("app", "ClickyApp.onCreate")
    }

    companion object {
        @Volatile
        private var app: ClickyApp? = null

        fun appContextOrNull(): Context? = app?.applicationContext
    }
}
