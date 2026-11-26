package com.coderarchitech.jobassistant

import com.coderarchitech.jobassistant.core.model.BuildInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Exposes the generated `BuildConfig` to other modules as a [BuildInfo]. */
@Module
@InstallIn(SingletonComponent::class)
object BuildInfoModule {
    @Provides
    @Singleton
    fun provideBuildInfo(): BuildInfo =
        BuildInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            flavor = BuildConfig.FLAVOR,
            configHost = BuildConfig.CONFIG_HOST,
            configKeys =
                BuildConfig.CONFIG_KEYS
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty),
        )
}
