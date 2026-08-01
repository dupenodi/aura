package com.drishti

import android.app.Application
import com.drishti.ai.ApiKeyStore

class DrishtiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiKeyStore.init(this)
    }
}
