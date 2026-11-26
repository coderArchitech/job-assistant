package com.coderarchitech.jobassistant

import android.app.Application
import android.util.Log
import com.coderarchitech.jobassistant.core.security.log.RedactingTree
import com.coderarchitech.jobassistant.core.security.log.Redactor
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber
import javax.inject.Inject

/**
 * Application entry point; creates the Hilt component graph, plants the redacting log tree and starts loading
 * signed config.
 */
@HiltAndroidApp
class JobAssistantApp : Application() {
    @Inject lateinit var redactor: Redactor

    @Inject lateinit var configStartup: ConfigStartup

    /** Work that lives as long as the process. */
    private val processScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        // The only tree: every log line is redacted (T1.09). Release builds keep INFO and above.
        Timber.plant(RedactingTree(redactor, minPriority = if (BuildConfig.DEBUG) Log.VERBOSE else Log.INFO))
        configStartup.start(processScope)
    }
}
