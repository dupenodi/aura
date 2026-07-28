package com.drishti

import android.app.Application
import android.content.Context
import com.drishti.debug.RingBufferLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DrishtiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        app = this
        RingBufferLogger.log("app", "DrishtiApp.onCreate")
    }

    companion object {
        @Volatile
        private var app: DrishtiApp? = null

        fun appContextOrNull(): Context? = app?.applicationContext
    }
}
