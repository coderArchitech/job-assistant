package com.coderarchitech.jobassistant.core.network

import android.util.Log
import com.coderarchitech.jobassistant.core.model.BuildInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** Provides one `OkHttpClient` per [NetworkGroup], plus the shared [Json]. The app supplies [NetworkConfig]. */
@Module
@InstallIn(SingletonComponent::class)
internal object NetworkModule {
    private const val HTTP_LOG_TAG = "Http"

    @Provides
    @Singleton
    fun provideJson(): Json = NetworkJson

    @Provides
    @Singleton
    fun provideClientFactory(
        buildInfo: BuildInfo,
        config: NetworkConfig,
    ): HttpClientFactory =
        HttpClientFactory(
            base = OkHttpClient(),
            configHost = buildInfo.configHost,
            config = config,
            logger = { Log.d(HTTP_LOG_TAG, it) },
        )

    @Provides
    @Singleton
    @HttpClientFor(NetworkGroup.AI)
    fun provideAiClient(factory: HttpClientFactory): OkHttpClient = factory.create(NetworkGroup.AI)

    @Provides
    @Singleton
    @HttpClientFor(NetworkGroup.JOB_SOURCES)
    fun provideJobSourcesClient(factory: HttpClientFactory): OkHttpClient = factory.create(NetworkGroup.JOB_SOURCES)

    @Provides
    @Singleton
    @HttpClientFor(NetworkGroup.GOOGLE)
    fun provideGoogleClient(factory: HttpClientFactory): OkHttpClient = factory.create(NetworkGroup.GOOGLE)

    @Provides
    @Singleton
    @HttpClientFor(NetworkGroup.CONFIG)
    fun provideConfigClient(factory: HttpClientFactory): OkHttpClient = factory.create(NetworkGroup.CONFIG)
}
