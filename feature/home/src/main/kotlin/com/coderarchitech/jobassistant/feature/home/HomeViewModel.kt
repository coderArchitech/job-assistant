package com.coderarchitech.jobassistant.feature.home

import androidx.lifecycle.ViewModel
import com.coderarchitech.jobassistant.core.data.AppInfoRepository
import com.coderarchitech.jobassistant.core.model.BuildInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** What the Home screen shows. Grows with task T4.12; for now it identifies the build. */
data class HomeUiState(
    val versionName: String,
    val flavor: String,
    val isConfigHostSet: Boolean,
)

/** Supplies [HomeUiState] to the Home screen. */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        appInfoRepository: AppInfoRepository,
    ) : ViewModel() {
        val uiState: StateFlow<HomeUiState> =
            MutableStateFlow(appInfoRepository.buildInfo().toUiState()).asStateFlow()
    }

private fun BuildInfo.toUiState() =
    HomeUiState(versionName = versionName, flavor = flavor, isConfigHostSet = isConfigHostSet)
