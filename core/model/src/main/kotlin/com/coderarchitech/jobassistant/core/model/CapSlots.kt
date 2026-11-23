package com.coderarchitech.jobassistant.core.model

import java.time.LocalDate

/**
 * The daily apply caps (PRD FR6): each portal has its own cap, and one overall cap covers every portal.
 * An apply session takes a slot before it starts and does nothing at all if it gets none (design:
 * Portal automation, step 1). Days are local calendar days, so the counts start again at local midnight
 * with no job needed. Implemented over Room in `:core:data`.
 */
interface CapSlots {
    /**
     * Takes one slot for [portalId] today if both [portalCap] and [overallCap] still have room, in one
     * transaction, so concurrent callers can never take more than the caps allow. A cap of 0 turns
     * applying off: every call is denied. The caps are passed in, so a cap lowered during the day applies at once.
     */
    suspend fun take(
        portalId: Long,
        portalCap: Int,
        overallCap: Int,
    ): SlotResult

    /**
     * Gives [slot] back, for a session that never reached the portal. Do not release a slot after the
     * portal was contacted: an attempt that touched the site counts against the day. Release each slot
     * at most once; a count never goes below zero. The slot is credited to the day it was taken, so a
     * release after midnight changes nothing today.
     */
    suspend fun release(slot: CapSlot)

    /** How many slots are left today for [portalId] and overall, for the screens. Never negative. */
    suspend fun remaining(
        portalId: Long,
        portalCap: Int,
        overallCap: Int,
    ): RemainingSlots
}

/** A slot taken for one portal on one day. Hand it back to [CapSlots.release] if it goes unused. */
data class CapSlot(
    val portalId: Long,
    val day: LocalDate,
)

/** Result of [CapSlots.take]. */
sealed interface SlotResult {
    /** A slot was taken; the session may start. */
    data class Taken(
        val slot: CapSlot,
    ) : SlotResult

    /** No slot was taken, because [limit] is used up for today. */
    data class Denied(
        val limit: CapLimit,
    ) : SlotResult
}

/** Which cap stopped a session. */
enum class CapLimit {
    /** The portal's own daily cap. */
    PORTAL,

    /** The overall daily cap across all portals. */
    OVERALL,
}

/** Slots left today. */
data class RemainingSlots(
    val portal: Int,
    val overall: Int,
) {
    /** What one more session can still use: the smaller of the two. */
    val usable: Int get() = minOf(portal, overall)
}
