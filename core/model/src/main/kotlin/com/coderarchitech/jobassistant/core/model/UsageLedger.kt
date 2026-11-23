package com.coderarchitech.jobassistant.core.model

import java.time.Instant

/**
 * Where AI spend is recorded and summed. The budget guard (task T1.14) reads it before every call and
 * writes to it after, so month-to-date spend is always what the user has really been charged for.
 * Implemented over Room in `:core:data`.
 */
interface UsageLedger {
    /**
     * Total estimated cost in micro-US-dollars of calls recorded at or after [from], for [task] only
     * when it is given.
     */
    suspend fun spentMicrosSince(
        from: Instant,
        task: AiTask? = null,
    ): Long

    /** Records one AI call. */
    suspend fun record(usage: UsageRecord)
}

/**
 * The user's current spending limits. Throws when they cannot be read (settings storage fails after
 * repeated I/O errors); the budget guard then refuses calls instead of assuming a budget.
 */
fun interface BudgetSource {
    /** The limits now in force. */
    suspend fun budget(): Budget
}
