package com.coderarchitech.jobassistant

import com.coderarchitech.jobassistant.core.model.IoDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Provides the app's coroutine dispatchers. It lives in `:app`, which sees every module, because
 * several library modules (`:core:data`, `:core:security`) inject [IoDispatcher].
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
