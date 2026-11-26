package com.coderarchitech.jobassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.coderarchitech.jobassistant.core.ui.JobAssistantTheme
import com.coderarchitech.jobassistant.feature.home.HomeRoute
import com.coderarchitech.jobassistant.feature.settings.usage.UsageRoute
import com.coderarchitech.jobassistant.feature.settings.watchlist.WatchlistRoute
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity. The navigation graph arrives with the app shell (task T1.17); until then Home opens
 * the few screens that exist, and the choice survives rotation and process death.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            JobAssistantTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                val home = { screen = Screen.HOME }
                if (screen != Screen.HOME) BackHandler(onBack = home)
                when (screen) {
                    Screen.HOME ->
                        HomeRoute(onOpenWatchlist = { screen = Screen.WATCHLIST }, onOpenUsage = {
                            screen =
                                Screen.USAGE
                        })
                    Screen.WATCHLIST -> WatchlistRoute(onBack = home)
                    Screen.USAGE -> UsageRoute(onBack = home)
                }
            }
        }
    }
}

/** The screens Home can open until the navigation graph arrives (T1.17). */
private enum class Screen { HOME, WATCHLIST, USAGE }
