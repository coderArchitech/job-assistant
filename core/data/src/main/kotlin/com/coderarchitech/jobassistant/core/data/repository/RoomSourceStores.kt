package com.coderarchitech.jobassistant.core.data.repository

import com.coderarchitech.jobassistant.core.data.db.dao.JobDao
import com.coderarchitech.jobassistant.core.data.db.dao.SourceCursorDao
import com.coderarchitech.jobassistant.core.data.db.entity.toEntity
import com.coderarchitech.jobassistant.core.data.db.entity.toModel
import com.coderarchitech.jobassistant.core.model.KnownPostings
import com.coderarchitech.jobassistant.core.model.SourceCursor
import com.coderarchitech.jobassistant.core.model.SourceCursorStore
import javax.inject.Inject

/** The [SourceCursorStore] over the `source_cursor` table. */
internal class RoomSourceCursorStore
    @Inject
    constructor(
        private val dao: SourceCursorDao,
    ) : SourceCursorStore {
        override suspend fun get(sourceKey: String): SourceCursor? = dao.get(sourceKey)?.toModel()

        override suspend fun save(cursor: SourceCursor) = dao.upsert(cursor.toEntity())
    }

/** The [KnownPostings] over the `job` table's (source, externalId) key. */
internal class RoomKnownPostings
    @Inject
    constructor(
        private val dao: JobDao,
    ) : KnownPostings {
        override suspend fun knownExternalIds(
            sourceKey: String,
            externalIds: Collection<String>,
        ): Set<String> =
            // SQLite allows 999 variables per statement on older Android versions, so ask in slices.
            externalIds.distinct().chunked(SLICE).flatMapTo(HashSet()) { dao.existingExternalIds(sourceKey, it) }

        private companion object {
            const val SLICE = 500
        }
    }
