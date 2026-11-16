package com.coderarchitech.jobassistant.feature.home

import com.coderarchitech.jobassistant.core.data.AppInfoRepository
import com.coderarchitech.jobassistant.core.model.BuildInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeViewModelTest {
    private class FakeAppInfoRepository(
        private val info: BuildInfo,
    ) : AppInfoRepository {
        override fun buildInfo() = info
    }

    @Test
    fun uiStateShowsTheBuild() {
        val viewModel = HomeViewModel(FakeAppInfoRepository(BuildInfo("0.1.0-dev", "dev", configHost = "")))

        assertEquals(
            HomeUiState(versionName = "0.1.0-dev", flavor = "dev", isConfigHostSet = false),
            viewModel.uiState.value,
        )
    }
}
