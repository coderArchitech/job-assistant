package com.coderarchitech.jobassistant.feature.settings.prices

import com.coderarchitech.jobassistant.core.config.ConfigPrices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Reads the signed price table for the editor. */
@Module
@InstallIn(SingletonComponent::class)
object PricesModule {
    @Provides
    fun provideSignedPrices(prices: ConfigPrices): SignedPrices =
        SignedPrices {
            prices.load()
            prices.listed()
        }
}
