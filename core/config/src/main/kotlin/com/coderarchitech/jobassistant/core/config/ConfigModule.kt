package com.coderarchitech.jobassistant.core.config

import android.content.Context
import com.coderarchitech.jobassistant.core.model.BuildInfo
import com.coderarchitech.jobassistant.core.model.IoDispatcher
import com.coderarchitech.jobassistant.core.network.HttpClientFor
import com.coderarchitech.jobassistant.core.network.NetworkGroup
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Singleton

/** Wires the config host client: the CONFIG network group, the app's keys, app-private storage and the APK. */
@Module
@InstallIn(SingletonComponent::class)
object ConfigModule {
    @Provides
    @Singleton
    fun provideConfigClient(
        @HttpClientFor(NetworkGroup.CONFIG) http: OkHttpClient,
        buildInfo: BuildInfo,
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ): ConfigClient =
        ConfigClient(
            http = http,
            buildInfo = buildInfo,
            signatures = ConfigSignatures(buildInfo.configKeys),
            store = FileConfigStore(File(context.filesDir, "config")),
            bundled = assetsOf(context),
            io = io,
        )

    @Provides
    @Singleton
    fun provideConfigPrices(client: ConfigClient): ConfigPrices = ConfigPrices(client)

    private fun assetsOf(context: Context) =
        BundledConfig { path ->
            try {
                context.assets.open("config/$path").use { it.readBytes() }
            } catch (_: FileNotFoundException) {
                null
            }
        }
}
