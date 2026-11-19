package com.coderarchitech.jobassistant.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.coderarchitech.jobassistant.core.model.LoginMethod
import com.coderarchitech.jobassistant.core.model.Portal
import com.coderarchitech.jobassistant.core.model.PortalType
import com.coderarchitech.jobassistant.core.model.SessionStatus

/**
 * Stored form of [Portal]. There is no credential column: the username and password live in the
 * vault under `portal/<id>`, never in Room (CLAUDE.md rule 3).
 */
@Entity(tableName = "portal")
data class PortalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: PortalType,
    val adapterId: String?,
    val loginUrl: String,
    val searchUrl: String?,
    val loginMethod: LoginMethod,
    val dailyCap: Int,
    val enabled: Boolean,
    val autoSubmitHigh: Boolean,
    val lastSessionStatus: SessionStatus,
)

/** Maps a portal to its stored row. */
fun Portal.toEntity() =
    PortalEntity(
        id = id,
        name = name,
        type = type,
        adapterId = adapterId,
        loginUrl = loginUrl,
        searchUrl = searchUrl,
        loginMethod = loginMethod,
        dailyCap = dailyCap,
        enabled = enabled,
        autoSubmitHigh = autoSubmitHigh,
        lastSessionStatus = lastSessionStatus,
    )

/** Maps a stored portal row back to the domain type. */
fun PortalEntity.toModel() =
    Portal(
        id = id,
        name = name,
        type = type,
        adapterId = adapterId,
        loginUrl = loginUrl,
        searchUrl = searchUrl,
        loginMethod = loginMethod,
        dailyCap = dailyCap,
        enabled = enabled,
        autoSubmitHigh = autoSubmitHigh,
        lastSessionStatus = lastSessionStatus,
    )
