package com.coderarchitech.jobassistant

import com.coderarchitech.jobassistant.ai.ListedPriceTable
import com.coderarchitech.jobassistant.ai.PriceTable
import com.coderarchitech.jobassistant.core.config.ConfigPrices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Prices AI calls from the signed price table (task T3.09); unlisted models fall back to over-counting. */
@Module
@InstallIn(SingletonComponent::class)
object PricingModule {
    @Provides
    fun providePriceTable(prices: ConfigPrices): PriceTable = ListedPriceTable(prices::listed)
}
