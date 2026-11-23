package com.coderarchitech.jobassistant.core.data.db.entity

import androidx.room.Entity
import java.time.LocalDate

/**
 * How many apply slots were taken on one local day for one scope: [OVERALL] or `portal/<id>`.
 * One row per day and scope; a new day simply starts new rows, which is the reset at local midnight.
 */
@Entity(tableName = "cap_usage", primaryKeys = ["day", "scope"])
data class CapUsageEntity(
    val day: LocalDate,
    val scope: String,
    val used: Int,
) {
    /** Shared values. */
    companion object {
        /** The scope that counts applies across all portals. */
        const val OVERALL = "overall"

        /** The scope for one portal. */
        fun portalScope(portalId: Long) = "portal/$portalId"
    }
}
