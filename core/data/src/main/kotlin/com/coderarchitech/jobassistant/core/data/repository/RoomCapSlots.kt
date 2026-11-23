package com.coderarchitech.jobassistant.core.data.repository

import androidx.room.withTransaction
import com.coderarchitech.jobassistant.core.data.db.AgentDatabase
import com.coderarchitech.jobassistant.core.data.db.entity.CapUsageEntity
import com.coderarchitech.jobassistant.core.model.CapLimit
import com.coderarchitech.jobassistant.core.model.CapSlot
import com.coderarchitech.jobassistant.core.model.CapSlots
import com.coderarchitech.jobassistant.core.model.RemainingSlots
import com.coderarchitech.jobassistant.core.model.SlotResult
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/**
 * The [CapSlots] over the `cap_usage` table. A slot is taken with two guarded `UPDATE ... WHERE used < cap`
 * statements in one transaction, so two sessions racing for the last slot cannot both get it.
 */
internal class RoomCapSlots
    @Inject
    constructor(
        private val db: AgentDatabase,
        private val clock: Clock,
    ) : CapSlots {
        private val dao get() = db.capUsageDao()

        override suspend fun take(
            portalId: Long,
            portalCap: Int,
            overallCap: Int,
        ): SlotResult =
            when {
                // A cap of zero (or below) means applying is off: refuse without touching the database.
                portalCap <= 0 -> SlotResult.Denied(CapLimit.PORTAL)
                overallCap <= 0 -> SlotResult.Denied(CapLimit.OVERALL)
                else -> takeSlot(portalId, portalCap, overallCap)
            }

        private suspend fun takeSlot(
            portalId: Long,
            portalCap: Int,
            overallCap: Int,
        ): SlotResult =
            db.withTransaction {
                val day = today()
                // Counts for earlier days are never read again; trimming here keeps the table small.
                dao.deleteBefore(day.minusDays(RETENTION_DAYS))
                val portalScope = CapUsageEntity.portalScope(portalId)
                dao.ensure(CapUsageEntity(day, portalScope, 0))
                dao.ensure(CapUsageEntity(day, CapUsageEntity.OVERALL, 0))
                when {
                    dao.takeIfBelow(day, portalScope, portalCap) == 0 -> SlotResult.Denied(CapLimit.PORTAL)
                    dao.takeIfBelow(day, CapUsageEntity.OVERALL, overallCap) == 0 -> {
                        // The portal has room but the overall cap does not: undo the portal's slot.
                        dao.giveBack(day, portalScope)
                        SlotResult.Denied(CapLimit.OVERALL)
                    }
                    else -> SlotResult.Taken(CapSlot(portalId, day))
                }
            }

        override suspend fun release(slot: CapSlot) {
            db.withTransaction {
                // Both counts or neither: a slot that was already given back finds a zero and changes nothing.
                val portalScope = CapUsageEntity.portalScope(slot.portalId)
                if (dao.giveBack(slot.day, portalScope) == 1) dao.giveBack(slot.day, CapUsageEntity.OVERALL)
            }
        }

        override suspend fun remaining(
            portalId: Long,
            portalCap: Int,
            overallCap: Int,
        ): RemainingSlots =
            // One transaction, so the two counts come from the same moment.
            db.withTransaction {
                val day = today()
                val portalUsed = dao.used(day, CapUsageEntity.portalScope(portalId)) ?: 0
                val overallUsed = dao.used(day, CapUsageEntity.OVERALL) ?: 0
                RemainingSlots(
                    portal = (portalCap - portalUsed).coerceAtLeast(0),
                    overall = (overallCap - overallUsed).coerceAtLeast(0),
                )
            }

        private fun today(): LocalDate = LocalDate.now(clock)

        private companion object {
            /** Days of counts kept; the last week is useful when something looks wrong. */
            const val RETENTION_DAYS = 7L
        }
    }
