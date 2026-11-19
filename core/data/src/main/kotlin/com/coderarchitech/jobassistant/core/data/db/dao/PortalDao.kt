package com.coderarchitech.jobassistant.core.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.coderarchitech.jobassistant.core.data.db.entity.PortalEntity
import kotlinx.coroutines.flow.Flow

/** Reads and writes portals. Credentials are not here: they live in the vault. */
@Dao
interface PortalDao {
    /** Inserts a portal and returns its new id, which names its vault slot `portal/<id>`. */
    @Insert
    suspend fun insert(portal: PortalEntity): Long

    /** Updates a portal by id. */
    @Update
    suspend fun update(portal: PortalEntity)

    /** Deletes a portal; applications made through it keep their record with no portal. */
    @Delete
    suspend fun delete(portal: PortalEntity)

    /** The portal with [id], or null. */
    @Query("SELECT * FROM portal WHERE id = :id")
    suspend fun getById(id: Long): PortalEntity?

    /** Portals the agent may use on a run. */
    @Query("SELECT * FROM portal WHERE enabled = 1 ORDER BY id")
    suspend fun getEnabled(): List<PortalEntity>

    /** Emits every portal, oldest first, whenever the table changes. */
    @Query("SELECT * FROM portal ORDER BY id")
    fun observeAll(): Flow<List<PortalEntity>>
}
