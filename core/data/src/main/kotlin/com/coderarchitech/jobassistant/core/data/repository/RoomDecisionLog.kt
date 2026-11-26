package com.coderarchitech.jobassistant.core.data.repository

import com.coderarchitech.jobassistant.core.data.db.dao.DecisionDao
import com.coderarchitech.jobassistant.core.data.db.entity.DecisionEntity
import com.coderarchitech.jobassistant.core.data.db.entity.toModel
import com.coderarchitech.jobassistant.core.model.Decision
import com.coderarchitech.jobassistant.core.model.DecisionExample
import com.coderarchitech.jobassistant.core.model.DecisionLog
import com.coderarchitech.jobassistant.core.model.Verdict
import java.time.Clock
import javax.inject.Inject

/** The [DecisionLog] over the `decision` table. */
internal class RoomDecisionLog
    @Inject
    constructor(
        private val dao: DecisionDao,
        private val clock: Clock,
    ) : DecisionLog {
        override suspend fun record(
            jobId: Long,
            verdict: Verdict,
            reason: String?,
        ): Decision {
            val note = reason?.trim()?.takeIf { it.isNotEmpty() }?.cutAt(DecisionLog.MAX_REASON_LENGTH)
            val entity = DecisionEntity(jobId = jobId, verdict = verdict, reason = note, decidedAt = clock.instant())
            return entity.copy(id = dao.insert(entity)).toModel()
        }

        override suspend fun latestExamples(limit: Int): List<DecisionExample> {
            require(limit > 0) { "Ask for at least one example" }
            return dao.getLatestExamples(limit)
        }
    }

/** At most [max] characters, never ending in half of a surrogate pair (half an emoji). */
private fun String.cutAt(max: Int): String {
    if (length <= max) return this
    val end = if (Character.isHighSurrogate(this[max - 1])) max - 1 else max
    return substring(0, end)
}
