package com.coderarchitech.jobassistant

import com.coderarchitech.jobassistant.core.network.NetworkConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Tells `:core:network` whether this build may log HTTP headers (debug builds only). */
@Module
@InstallIn(SingletonComponent::class)
object NetworkConfigModule {
    @Provides
    fun provideNetworkConfig(): NetworkConfig = NetworkConfig(httpLogging = BuildConfig.HTTP_LOGGING)
}
