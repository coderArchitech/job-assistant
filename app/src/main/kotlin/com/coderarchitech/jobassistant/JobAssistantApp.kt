package com.coderarchitech.jobassistant

import android.app.Application
import com.coderarchitech.jobassistant.spike.SpikeEntry
import dagger.hilt.android.HiltAndroidApp

/** Application entry point; creates the Hilt component graph. */
@HiltAndroidApp
class JobAssistantApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // T1.01 spike: a call R8 cannot prove dead, so the client code stays in the APK being measured.
        System.getProperty("jobassistant.spike.url")?.let { SpikeEntry.run(it) }
    }
}
