package com.coderarchitech.jobassistant

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import com.coderarchitech.jobassistant.feature.home.HomeViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task T0.03: a ViewModel gets a repository through Hilt. The activity's Hilt ViewModel factory
 * builds [HomeViewModel], which needs `AppInfoRepository` from `:core:data`, which needs
 * `BuildInfo` from the app module.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class HiltWiringTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun homeViewModelGetsItsRepositoryThroughHilt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val viewModel = ViewModelProvider(activity)[HomeViewModel::class.java]
                val state = viewModel.uiState.value

                assertEquals(BuildConfig.VERSION_NAME, state.versionName)
                assertEquals(BuildConfig.FLAVOR, state.flavor)
            }
        }
    }
}
