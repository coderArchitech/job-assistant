package com.coderarchitech.jobassistant.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.coderarchitech.jobassistant.core.data.db.entity.CapUsageEntity
import java.time.LocalDate

/** Counts apply slots per day. Call it only through `RoomCapSlots`, which wraps the steps in a transaction. */
@Dao
interface CapUsageDao {
    /** Makes sure the row for [day] and [scope] exists, starting at zero. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensure(row: CapUsageEntity)

    /** Adds one to the count only if it is still below [cap], in one statement. Returns rows changed (1 or 0). */
    @Query("UPDATE cap_usage SET used = used + 1 WHERE day = :day AND scope = :scope AND used < :cap")
    suspend fun takeIfBelow(
        day: LocalDate,
        scope: String,
        cap: Int,
    ): Int

    /** Takes one off the count unless it is already zero. Returns rows changed (1 or 0). */
    @Query("UPDATE cap_usage SET used = used - 1 WHERE day = :day AND scope = :scope AND used > 0")
    suspend fun giveBack(
        day: LocalDate,
        scope: String,
    ): Int

    /** The count for [day] and [scope], or null when nothing was taken. */
    @Query("SELECT used FROM cap_usage WHERE day = :day AND scope = :scope")
    suspend fun used(
        day: LocalDate,
        scope: String,
    ): Int?

    /** Deletes counts for days before [day]. */
    @Query("DELETE FROM cap_usage WHERE day < :day")
    suspend fun deleteBefore(day: LocalDate): Int
}
