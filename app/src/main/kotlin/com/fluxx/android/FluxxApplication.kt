package com.fluxx.android

import android.app.Application
import android.util.Log

class FluxxApplication : Application() {

    companion object {
        private const val TAG = "FluxxApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Fluxx application initialized. Preparing core engine...")
        
        // In the future, telemetry/crash reporting setup goes here:
        // - [ ] Decide on crash reporting/telemetry now — cheap to add at v0.1, painful to retrofit into a hand-tuned zero-allocation hot path later.
    }
}
