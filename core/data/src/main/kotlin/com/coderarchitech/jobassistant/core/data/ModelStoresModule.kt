package com.coderarchitech.jobassistant.core.data

import com.coderarchitech.jobassistant.core.data.repository.RoomCapSlots
import com.coderarchitech.jobassistant.core.data.repository.RoomDecisionLog
import com.coderarchitech.jobassistant.core.data.repository.RoomKnownPostings
import com.coderarchitech.jobassistant.core.data.repository.RoomSourceCursorStore
import com.coderarchitech.jobassistant.core.data.repository.RoomUsageLedger
import com.coderarchitech.jobassistant.core.data.repository.SettingsBudgetSource
import com.coderarchitech.jobassistant.core.model.BudgetSource
import com.coderarchitech.jobassistant.core.model.CapSlots
import com.coderarchitech.jobassistant.core.model.DecisionLog
import com.coderarchitech.jobassistant.core.model.KnownPostings
import com.coderarchitech.jobassistant.core.model.SourceCursorStore
import com.coderarchitech.jobassistant.core.model.UsageLedger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the store interfaces that `:core:model` declares for other modules (`:ai`, `:sources`, `:agent`) to
 * their Room and settings implementations. Kept apart from [DataModule], which binds `:core:data`'s own.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ModelStoresModule {
    @Binds
    abstract fun bindUsageLedger(impl: RoomUsageLedger): UsageLedger

    @Binds
    abstract fun bindBudgetSource(impl: SettingsBudgetSource): BudgetSource

    @Binds
    abstract fun bindCapSlots(impl: RoomCapSlots): CapSlots

    @Binds
    abstract fun bindSourceCursorStore(impl: RoomSourceCursorStore): SourceCursorStore

    @Binds
    abstract fun bindKnownPostings(impl: RoomKnownPostings): KnownPostings

    @Binds
    abstract fun bindDecisionLog(impl: RoomDecisionLog): DecisionLog
}
