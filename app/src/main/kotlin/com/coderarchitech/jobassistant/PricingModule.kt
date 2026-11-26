package com.coderarchitech.jobassistant

import com.coderarchitech.jobassistant.ai.ListedPriceTable
import com.coderarchitech.jobassistant.ai.PriceTable
import com.coderarchitech.jobassistant.core.config.ConfigPrices
import com.coderarchitech.jobassistant.core.model.withEdits
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Prices AI calls from the user's edited prices, then the signed price table (task T3.09); unlisted models fall
 * back to over-counting.
 */
@Module
@InstallIn(SingletonComponent::class)
object PricingModule {
    @Provides
    fun providePriceTable(
        prices: ConfigPrices,
        userPrices: UserPrices,
    ): PriceTable =
        ListedPriceTable {
            // Before the edits are read, nothing is listed, so every model is over-counted rather than under.
            userPrices.current()?.let { prices.listed().withEdits(it) }.orEmpty()
        }
}
