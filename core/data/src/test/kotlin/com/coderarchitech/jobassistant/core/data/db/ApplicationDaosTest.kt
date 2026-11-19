package com.coderarchitech.jobassistant.core.data.db

import com.coderarchitech.jobassistant.core.data.db.entity.toEntity
import com.coderarchitech.jobassistant.core.data.db.entity.toModel
import com.coderarchitech.jobassistant.core.model.ApplicationStatus
import com.coderarchitech.jobassistant.core.model.OutreachStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ApplicationDaosTest : DatabaseTest() {
    private suspend fun insertJob(externalId: String = "1"): Long =
        db.jobDao().insert(sampleJob(externalId = externalId, canonicalUrl = "https://x.test/$externalId").toEntity())

    @Test
    fun applicationRoundTripsThroughTheDatabase() =
        runTest {
            val job = insertJob()
            val portal = db.portalDao().insert(samplePortal().toEntity())
            val id = db.applicationDao().insert(sampleApplication(job, portal).toEntity())

            assertEquals(sampleApplication(job, portal).copy(id = id), db.applicationDao().getByJobId(job)?.toModel())
        }

    @Test
    fun aJobCanHaveOnlyOneApplication() =
        runTest {
            val job = insertJob()
            db.applicationDao().insert(sampleApplication(job).toEntity())

            assertConstraintViolation { db.applicationDao().insert(sampleApplication(job).toEntity()) }
            assertEquals(
                1,
                db
                    .applicationDao()
                    .observeAll()
                    .first()
                    .size,
            )
        }

    @Test
    fun anApplicationForAJobThatDoesNotExistIsRejected() =
        runTest {
            assertConstraintViolation { db.applicationDao().insert(sampleApplication(jobId = 999).toEntity()) }
        }

    @Test
    fun aJobWithAnApplicationCannotBeDeleted() =
        runTest {
            val job = insertJob()
            db.applicationDao().insert(sampleApplication(job).toEntity())

            assertConstraintViolation { db.openHelper.writableDatabase.execSQL("DELETE FROM job WHERE id = $job") }
            assertNotNull(db.jobDao().getById(job))
            assertNotNull(db.applicationDao().getByJobId(job))
        }

    @Test
    fun deletingAPortalKeepsTheApplicationWithNoPortal() =
        runTest {
            val job = insertJob()
            val portal = db.portalDao().insert(samplePortal().toEntity())
            db.applicationDao().insert(sampleApplication(job, portal).toEntity())

            db.portalDao().delete(samplePortal(id = portal).toEntity())

            assertNull(db.applicationDao().getByJobId(job)?.portalId)
            assertNotNull(db.applicationDao().getByJobId(job))
        }

    @Test
    fun applicationsAreFoundByStatus() =
        runTest {
            val first = insertJob("1")
            val second = insertJob("2")
            val checkManually = sampleApplication(first, status = ApplicationStatus.CHECK_MANUALLY)
            val applyManually = sampleApplication(second, status = ApplicationStatus.APPLY_MANUALLY)
            db.applicationDao().insert(checkManually.toEntity())
            db.applicationDao().insert(applyManually.toEntity())

            assertEquals(
                listOf(first),
                db.applicationDao().getByStatus(ApplicationStatus.CHECK_MANUALLY).map { it.jobId },
            )
        }

    @Test
    fun outreachMessageRoundTripsAndIsDeletedWithItsJob() =
        runTest {
            val job = insertJob()
            val id = db.outreachMessageDao().insert(sampleOutreach(job).toEntity())

            assertEquals(sampleOutreach(job).copy(id = id), db.outreachMessageDao().getById(id)?.toModel())

            db.openHelper.writableDatabase.execSQL("DELETE FROM job WHERE id = $job")

            assertNull(db.outreachMessageDao().getById(id))
        }

    @Test
    fun outreachIsListedPerJobAndPerStatus() =
        runTest {
            val job = insertJob()
            val sent = db.outreachMessageDao().insert(sampleOutreach(job, OutreachStatus.SENT).toEntity())
            db.outreachMessageDao().insert(sampleOutreach(job, OutreachStatus.DRAFT).toEntity())

            assertEquals(
                2,
                db
                    .outreachMessageDao()
                    .observeForJob(job)
                    .first()
                    .size,
            )
            val sentIds =
                db
                    .outreachMessageDao()
                    .observeByStatus(OutreachStatus.SENT)
                    .first()
                    .map { it.id }
            assertEquals(listOf(sent), sentIds)
        }

    @Test
    fun onlyUnrepliedSentMessagesWhoseFollowUpIsDueAreReturned() =
        runTest {
            val job = insertJob()
            val due = sampleOutreach(job, followUpDueAt = T0)
            val dueId = db.outreachMessageDao().insert(due.toEntity())
            val dao = db.outreachMessageDao()
            dao.insert(sampleOutreach(job, followUpDueAt = T0.plusSeconds(60)).toEntity()) // not due yet
            dao.insert(sampleOutreach(job, followUpDueAt = T0, repliedAt = T0).toEntity()) // replied
            dao.insert(sampleOutreach(job, OutreachStatus.DRAFT, followUpDueAt = T0).toEntity())
            dao.insert(sampleOutreach(job, followUpDueAt = null).toEntity()) // no follow-up set

            assertEquals(listOf(dueId), dao.getFollowUpsDue(now = T0).map { it.id })
        }
}
