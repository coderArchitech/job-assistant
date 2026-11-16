package com.coderarchitech.jobassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.coderarchitech.jobassistant.core.ui.JobAssistantTheme
import com.coderarchitech.jobassistant.feature.home.HomeRoute
import dagger.hilt.android.AndroidEntryPoint

/** The single activity. Navigation between screens arrives with the app shell (task T1.17). */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            JobAssistantTheme {
                HomeRoute()
            }
        }
    }
}
