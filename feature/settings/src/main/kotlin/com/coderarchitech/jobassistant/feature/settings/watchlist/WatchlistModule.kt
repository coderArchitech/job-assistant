package com.coderarchitech.jobassistant.feature.settings.watchlist

import com.coderarchitech.jobassistant.agent.watchlist.BoardChecker
import com.coderarchitech.jobassistant.agent.watchlist.SourceBoardChecker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Checks boards through the real ATS clients. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class WatchlistModule {
    @Binds
    abstract fun bindBoardChecker(impl: SourceBoardChecker): BoardChecker
}
