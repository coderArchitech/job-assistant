package com.coderarchitech.jobassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.coderarchitech.jobassistant.core.ui.JobAssistantTheme
import com.coderarchitech.jobassistant.feature.home.HomeRoute
import com.coderarchitech.jobassistant.spike.SpikeRunner
import dagger.hilt.android.AndroidEntryPoint

/** The single activity. Navigation between screens arrives with the app shell (task T1.17). */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // T1.01 spike: `am start ... --ez spike true` runs the client check inside the shrunk app.
        if (intent.getBooleanExtra("spike", false)) SpikeRunner.runAndLog()
        setContent {
            JobAssistantTheme {
                HomeRoute()
            }
        }
    }
}
