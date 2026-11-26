package com.coderarchitech.jobassistant.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Home destination: connects [HomeViewModel] to [HomeScreen]. */
@Composable
fun HomeRoute(
    onOpenWatchlist: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(uiState = uiState, onOpenWatchlist = onOpenWatchlist, modifier = modifier)
}

/** Stateless Home screen. */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onOpenWatchlist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.home_version, uiState.versionName, uiState.flavor),
                style = MaterialTheme.typography.bodyMedium,
            )
            // Until the app shell (T1.17) brings navigation, Home links to the screens that exist.
            FilledTonalButton(onClick = onOpenWatchlist) { Text(stringResource(R.string.home_watchlist)) }
        }
    }
}

@Preview
@Composable
private fun HomeScreenPreview() {
    HomeScreen(HomeUiState(versionName = "0.1.0-dev", flavor = "dev", isConfigHostSet = false), onOpenWatchlist = {})
}
