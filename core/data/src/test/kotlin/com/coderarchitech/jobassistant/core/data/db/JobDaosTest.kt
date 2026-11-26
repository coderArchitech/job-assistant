package com.coderarchitech.jobassistant.core.data.db

import com.coderarchitech.jobassistant.core.data.db.entity.toEntity
import com.coderarchitech.jobassistant.core.data.db.entity.toModel
import com.coderarchitech.jobassistant.core.model.DraftKind
import com.coderarchitech.jobassistant.core.model.JobStatus
import com.coderarchitech.jobassistant.core.model.Salary
import com.coderarchitech.jobassistant.core.model.SalaryPeriod
import com.coderarchitech.jobassistant.core.model.Verdict
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JobDaosTest : DatabaseTest() {
    private suspend fun insertJob(
        externalId: String = "123",
        status: JobStatus = JobStatus.FOUND,
        canonicalUrl: String = "https://boards.example.test/acme/jobs/$externalId",
        dedupeHash: String = "hash-$externalId",
    ): Long =
        db.jobDao().insert(
            sampleJob(externalId = externalId, status = status, canonicalUrl = canonicalUrl, dedupeHash = dedupeHash)
                .toEntity(),
        )

    @Test
    fun jobRoundTripsThroughTheDatabase() =
        runTest {
            val id = insertJob()
            val expected =
                sampleJob(id = id, canonicalUrl = "https://boards.example.test/acme/jobs/123", dedupeHash = "hash-123")

            assertEquals(expected, db.jobDao().getById(id)?.toModel())
        }

    @Test
    fun salaryWithOneBoundAndNoSalaryBothRoundTrip() =
        runTest {
            val oneBound = Salary(min = null, max = 50, currency = "USD", period = SalaryPeriod.HOUR)
            val withOneBound = db.jobDao().insert(sampleJob(externalId = "a", salary = oneBound).toEntity())
            val withoutSalary = db.jobDao().insert(sampleJob(externalId = "b", salary = null).toEntity())

            assertEquals(
                oneBound,
                db
                    .jobDao()
                    .getById(withOneBound)
                    ?.toModel()
                    ?.salary,
            )
            assertNull(
                db
                    .jobDao()
                    .getById(withoutSalary)
                    ?.toModel()
                    ?.salary,
            )
        }

    @Test
    fun insertingTheSameSourceAndExternalIdTwiceKeepsTheFirstRow() =
        runTest {
            val first = db.jobDao().insert(sampleJob(externalId = "dup", status = JobStatus.SCORED).toEntity())
            val second = db.jobDao().insert(sampleJob(externalId = "dup", status = JobStatus.FOUND).toEntity())

            assertEquals(-1L, second)
            assertEquals(JobStatus.SCORED, db.jobDao().findBySourceAndExternalId("greenhouse:acme", "dup")?.status)
            assertEquals(first, db.jobDao().findBySourceAndExternalId("greenhouse:acme", "dup")?.id)
        }

    @Test
    fun sameExternalIdFromAnotherSourceIsAnotherJob() =
        runTest {
            val one = db.jobDao().insert(sampleJob(source = "lever:acme", externalId = "1").toEntity())
            val two = db.jobDao().insert(sampleJob(source = "greenhouse:acme", externalId = "1").toEntity())

            assertNotEquals(one, two)
        }

    @Test
    fun dedupeLookupsFindByCanonicalUrlAndByHash() =
        runTest {
            val a = insertJob("a", canonicalUrl = "https://x.test/1", dedupeHash = "same")
            val b = insertJob("b", canonicalUrl = "https://x.test/2", dedupeHash = "same")

            assertEquals(listOf(a), db.jobDao().findByCanonicalUrl("https://x.test/1").map { it.id })
            assertEquals(listOf(a, b), db.jobDao().findByDedupeHash("same").map { it.id })
            assertEquals(emptyList<Long>(), db.jobDao().findByDedupeHash("none").map { it.id })
        }

    @Test
    fun statusQueriesAndCountsReflectStoredJobs() =
        runTest {
            insertJob("1", JobStatus.FOUND)
            insertJob("2", JobStatus.FOUND)
            val scored = insertJob("3", JobStatus.SCORED)

            assertEquals(2, db.jobDao().getByStatus(JobStatus.FOUND, limit = 10).size)
            assertEquals(1, db.jobDao().getByStatus(JobStatus.FOUND, limit = 1).size)
            assertEquals(
                listOf(scored),
                db
                    .jobDao()
                    .observeByStatus(JobStatus.SCORED)
                    .first()
                    .map { it.id },
            )
            assertEquals(
                mapOf(JobStatus.FOUND to 2, JobStatus.SCORED to 1),
                db
                    .jobDao()
                    .observeStatusCounts()
                    .first()
                    .associate { it.status to it.count },
            )
        }

    @Test
    fun deletingTheOriginalLeavesTheAliasAsAStandaloneJob() =
        runTest {
            val original = insertJob("orig")
            val alias = db.jobDao().insert(sampleJob(externalId = "alias", aliasOfJobId = original).toEntity())
            assertEquals(original, db.jobDao().getById(alias)?.aliasOfJobId)

            db.openHelper.writableDatabase.execSQL("DELETE FROM job WHERE id = $original")

            assertNull(db.jobDao().getById(alias)?.aliasOfJobId)
        }

    @Test
    fun matchResultsAreKeyedByJobAndProfileVersion() =
        runTest {
            val job = insertJob()
            val dao = db.matchResultDao()
            dao.upsert(sampleMatch(job, profileVersion = 1, score = 60).toEntity())
            dao.upsert(sampleMatch(job, profileVersion = 2, score = 90).toEntity())
            dao.upsert(sampleMatch(job, profileVersion = 1, score = 65).toEntity()) // replaces version 1

            assertEquals(65, dao.get(job, 1)?.score)
            assertEquals(sampleMatch(job, profileVersion = 2, score = 90), dao.getLatestForJob(job)?.toModel())
            assertNull(dao.get(job, 3))
        }

    @Test
    fun scoresForAProfileVersionComeBestFirst() =
        runTest {
            val low = insertJob("low")
            val high = insertJob("high")
            db.matchResultDao().upsert(sampleMatch(low, score = 30).toEntity())
            db.matchResultDao().upsert(sampleMatch(high, score = 95).toEntity())

            assertEquals(
                listOf(high, low),
                db
                    .matchResultDao()
                    .observeByProfileVersion(1)
                    .first()
                    .map { it.jobId },
            )
        }

    @Test
    fun aJobHasOneDraftPerKindAndInsertingAgainNeverOverwritesIt() =
        runTest {
            val job = insertJob()
            val dao = db.draftDao()
            val first = dao.insert(sampleDraft(job, DraftKind.COVER_LETTER, "first").toEntity())
            val again = dao.insert(sampleDraft(job, DraftKind.COVER_LETTER, "second").toEntity())
            dao.insert(sampleDraft(job, DraftKind.EMAIL, "mail").toEntity())

            assertEquals(-1L, again)
            assertEquals(first, dao.get(job, DraftKind.COVER_LETTER)?.id)
            assertEquals("first", dao.get(job, DraftKind.COVER_LETTER)?.content)
            assertEquals(listOf("first", "mail"), dao.observeForJob(job).first().map { it.content })
            assertEquals(listOf("Expected CTC"), dao.get(job, DraftKind.EMAIL)?.unknownFields)
        }

    @Test
    fun regeneratingReplacesAGeneratedDraftButKeepsItsId() =
        runTest {
            val job = insertJob()
            val dao = db.draftDao()
            val id = dao.insert(sampleDraft(job, DraftKind.COVER_LETTER, "first").toEntity())

            val changed = dao.regenerate(job, DraftKind.COVER_LETTER, "second", emptyList(), T0.plusSeconds(60))

            assertEquals(1, changed)
            val draft = dao.get(job, DraftKind.COVER_LETTER)
            assertEquals(id, draft?.id)
            assertEquals("second", draft?.content)
            assertEquals(emptyList<String>(), draft?.unknownFields)
            assertEquals(T0.plusSeconds(60), draft?.createdAt)
        }

    @Test
    fun aDraftTheUserEditedIsNeverRegeneratedOver() =
        runTest {
            val job = insertJob()
            val dao = db.draftDao()
            dao.insert(sampleDraft(job, DraftKind.COVER_LETTER, "generated").toEntity())

            assertEquals(1, dao.saveEdit(job, DraftKind.COVER_LETTER, "my words", emptyList()))
            val changed = dao.regenerate(job, DraftKind.COVER_LETTER, "regenerated", listOf("x"), T0)

            assertEquals(0, changed)
            val draft = dao.get(job, DraftKind.COVER_LETTER)
            assertEquals("my words", draft?.content)
            assertEquals(true, draft?.editedByUser)
            assertEquals(emptyList<String>(), draft?.unknownFields)
        }

    @Test
    fun editingOrRegeneratingAMissingDraftChangesNothing() =
        runTest {
            val job = insertJob()
            val dao = db.draftDao()

            assertEquals(0, dao.saveEdit(job, DraftKind.EMAIL, "x", emptyList()))
            assertEquals(0, dao.regenerate(job, DraftKind.EMAIL, "x", emptyList(), T0))
            assertNull(dao.get(job, DraftKind.EMAIL))
        }

    @Test
    fun theLatestDecisionOnAJobIsItsExample() =
        runTest {
            val job = insertJob()
            val dao = db.decisionDao()
            dao.insert(sampleDecision(job, Verdict.REJECT, T0).toEntity())
            dao.insert(sampleDecision(job, Verdict.APPROVE, T0.plusSeconds(60)).toEntity())
            dao.insert(sampleDecision(job, Verdict.REJECT, T0.plusSeconds(120)).toEntity())

            assertEquals(listOf(Verdict.REJECT), dao.getLatestExamples(limit = 2).map { it.verdict })
            assertEquals(T0.plusSeconds(120), dao.getLatestForJob(job)?.decidedAt)
        }

    @Test
    fun deletingAJobDeletesItsMatchDraftsAndDecisions() =
        runTest {
            val job = insertJob()
            db.matchResultDao().upsert(sampleMatch(job).toEntity())
            db.draftDao().insert(sampleDraft(job).toEntity())
            db.decisionDao().insert(sampleDecision(job).toEntity())

            db.openHelper.writableDatabase.execSQL("DELETE FROM job WHERE id = $job")

            assertNull(db.matchResultDao().getLatestForJob(job))
            assertNull(db.draftDao().get(job, DraftKind.COVER_LETTER))
            assertNull(db.decisionDao().getLatestForJob(job))
        }
}
