package com.coderarchitech.jobassistant.core.data.repository

import com.coderarchitech.jobassistant.core.data.db.dao.UsageRecordDao
import com.coderarchitech.jobassistant.core.data.db.entity.toEntity
import com.coderarchitech.jobassistant.core.data.settings.SettingsRepository
import com.coderarchitech.jobassistant.core.model.AiTask
import com.coderarchitech.jobassistant.core.model.Budget
import com.coderarchitech.jobassistant.core.model.BudgetSource
import com.coderarchitech.jobassistant.core.model.UsageLedger
import com.coderarchitech.jobassistant.core.model.UsageRecord
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject

/** The [UsageLedger] over the `usage_record` table. */
internal class RoomUsageLedger
    @Inject
    constructor(
        private val dao: UsageRecordDao,
    ) : UsageLedger {
        override suspend fun spentMicrosSince(
            from: Instant,
            task: AiTask?,
        ): Long = if (task == null) dao.sumCostMicrosSince(from) else dao.sumCostMicrosSince(from, task)

        override suspend fun record(usage: UsageRecord) {
            dao.insert(usage.toEntity())
        }
    }

/** The [BudgetSource] over the stored settings. It throws when the settings cannot be read. */
internal class SettingsBudgetSource
    @Inject
    constructor(
        private val settings: SettingsRepository,
    ) : BudgetSource {
        override suspend fun budget(): Budget = settings.settings.first().budget
    }
