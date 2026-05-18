package com.tx5dr.bridge

import android.app.Application

class BridgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BridgeRuntime.init(this)
    }
}
