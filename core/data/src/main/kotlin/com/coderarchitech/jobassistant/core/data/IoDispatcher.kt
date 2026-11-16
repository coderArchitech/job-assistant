package com.coderarchitech.jobassistant.core.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

/** Qualifies the [CoroutineDispatcher] for blocking disk and network work. Inject it; tests swap it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Provides the app's coroutine dispatchers. */
@Module
@InstallIn(SingletonComponent::class)
internal object DispatchersModule {
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
