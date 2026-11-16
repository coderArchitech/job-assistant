package com.coderarchitech.jobassistant.core.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the `:core:data` repository interfaces to their implementations. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {
    @Binds
    abstract fun bindAppInfoRepository(impl: DefaultAppInfoRepository): AppInfoRepository
}
