package com.coderarchitech.jobassistant.core.data.db

import com.coderarchitech.jobassistant.core.data.db.entity.toEntity
import com.coderarchitech.jobassistant.core.data.db.entity.toModel
import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.core.model.SessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Profile, search presets, watchlist, cursors and portals. */
class ConfigDaosTest : DatabaseTest() {
    @Test
    fun profileIsMissingBeforeOnboardingAndRoundTripsAfter() =
        runTest {
            assertNull(db.profileDao().get())

            db.profileDao().upsert(sampleProfile().toEntity())

            assertEquals(sampleProfile(), db.profileDao().get()?.toModel())
        }

    @Test
    fun savingTheProfileAgainReplacesTheSingleRow() =
        runTest {
            db.profileDao().upsert(sampleProfile(version = 1).toEntity())
            db.profileDao().upsert(sampleProfile(version = 2).toEntity())

            assertEquals(
                2,
                db
                    .profileDao()
                    .observe()
                    .first()
                    ?.version,
            )
            val rows =
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM profile").use {
                    it.moveToFirst()
                    it.getInt(0)
                }
            assertEquals(1, rows)
        }

    @Test
    fun presetRoundTripsAndCanBeUpdatedAndDeleted() =
        runTest {
            val id = db.searchPresetDao().insert(samplePreset().toEntity())
            assertEquals(samplePreset(id = id), db.searchPresetDao().getById(id)?.toModel())

            db.searchPresetDao().update(samplePreset(id = id, name = "Renamed").toEntity())
            assertEquals(
                "Renamed",
                db
                    .searchPresetDao()
                    .observeAll()
                    .first()
                    .single()
                    .name,
            )

            db.searchPresetDao().delete(samplePreset(id = id).toEntity())
            assertNull(db.searchPresetDao().getById(id))
        }

    @Test
    fun onlyEnabledPresetsRunOnASchedule() =
        runTest {
            val on = db.searchPresetDao().insert(samplePreset(name = "on").toEntity())
            db.searchPresetDao().insert(samplePreset(name = "off").toEntity().copy(enabled = false))

            assertEquals(listOf(on), db.searchPresetDao().getEnabled().map { it.id })
        }

    @Test
    fun deletingAPresetKeepsItsRunsInTheLog() =
        runTest {
            val preset = db.searchPresetDao().insert(samplePreset().toEntity())
            val run = db.runDao().insert(sampleRun(presetId = preset).toEntity())

            db.searchPresetDao().delete(samplePreset(id = preset).toEntity())

            assertNull(db.runDao().getById(run)?.presetId)
            assertEquals(run, db.runDao().getById(run)?.id)
        }

    @Test
    fun theSameBoardCannotBeWatchedTwiceButAnotherAtsCan() =
        runTest {
            db.companyWatchlistDao().insert(sampleWatchlistEntry(AtsType.GREENHOUSE, "acme").toEntity())
            db.companyWatchlistDao().insert(sampleWatchlistEntry(AtsType.LEVER, "acme").toEntity())

            assertConstraintViolation {
                db.companyWatchlistDao().insert(sampleWatchlistEntry(AtsType.GREENHOUSE, "acme").toEntity())
            }
            assertEquals(
                2,
                db
                    .companyWatchlistDao()
                    .observeAll()
                    .first()
                    .size,
            )
        }

    @Test
    fun disabledWatchlistCompaniesAreNotFetched() =
        runTest {
            val on = db.companyWatchlistDao().insert(sampleWatchlistEntry(boardSlug = "on").toEntity())
            db.companyWatchlistDao().insert(sampleWatchlistEntry(boardSlug = "off").toEntity().copy(enabled = false))

            val enabled = db.companyWatchlistDao().getEnabled()
            assertEquals(listOf(on), enabled.map { it.id })
            assertEquals(sampleWatchlistEntry(boardSlug = "on").copy(id = on), enabled.single().toModel())
        }

    @Test
    fun sourceCursorIsReplacedPerSourceKey() =
        runTest {
            db.sourceCursorDao().upsert(sampleCursor("remotive").toEntity())
            db.sourceCursorDao().upsert(sampleCursor("remoteok").toEntity())
            val newer =
                sampleCursor("remotive").copy(lastSeenExternalId = "ext-100", lastSuccessAt = T0.plusSeconds(60))
            db.sourceCursorDao().upsert(newer.toEntity())

            assertEquals(newer, db.sourceCursorDao().get("remotive")?.toModel())
            assertEquals(listOf("remoteok", "remotive"), db.sourceCursorDao().getAll().map { it.sourceKey })
            assertNull(db.sourceCursorDao().get("unknown"))
        }

    @Test
    fun portalRoundTripsAndKeepsItsVaultKey() =
        runTest {
            val id = db.portalDao().insert(samplePortal().toEntity())
            val stored = db.portalDao().getById(id)?.toModel()

            assertEquals(samplePortal(id = id), stored)
            assertEquals("portal/$id", stored?.vaultKey)
        }

    @Test
    fun portalSessionStatusAndEnabledFlagCanBeUpdated() =
        runTest {
            val id = db.portalDao().insert(samplePortal().toEntity())
            val off = samplePortal(id = id, enabled = false).copy(lastSessionStatus = SessionStatus.EXPIRED)

            db.portalDao().update(off.toEntity())

            assertEquals(emptyList<Long>(), db.portalDao().getEnabled().map { it.id })
            assertEquals(
                SessionStatus.EXPIRED,
                db
                    .portalDao()
                    .observeAll()
                    .first()
                    .single()
                    .lastSessionStatus,
            )
        }
}
