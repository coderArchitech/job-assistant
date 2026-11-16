package com.coderarchitech.jobassistant.core.data

import com.coderarchitech.jobassistant.core.model.BuildInfo
import javax.inject.Inject

/** Read access to facts about the installed app build. */
interface AppInfoRepository {
    /** The build the app is running as. */
    fun buildInfo(): BuildInfo
}

/** Returns the [BuildInfo] the app module provides from its generated `BuildConfig`. */
internal class DefaultAppInfoRepository
    @Inject
    constructor(
        private val buildInfo: BuildInfo,
    ) : AppInfoRepository {
        override fun buildInfo(): BuildInfo = buildInfo
    }
