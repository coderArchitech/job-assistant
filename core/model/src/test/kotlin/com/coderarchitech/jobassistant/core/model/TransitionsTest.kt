package com.coderarchitech.jobassistant.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionsTest {
    private fun edges(from: JobStatus) = JobTransitions.next(from)

    @Test
    fun jobPipelineEdgesAreExactlyTheDocumentedOnes() {
        val expected =
            mapOf(
                JobStatus.FOUND to setOf(JobStatus.SCORED),
                JobStatus.SCORED to setOf(JobStatus.APPROVED, JobStatus.SKIPPED),
                JobStatus.APPROVED to setOf(JobStatus.READY, JobStatus.FAILED),
                JobStatus.READY to setOf(JobStatus.NEEDS_INPUT, JobStatus.SUBMITTING, JobStatus.FAILED),
                JobStatus.NEEDS_INPUT to setOf(JobStatus.READY, JobStatus.SUBMITTING, JobStatus.FAILED),
                JobStatus.SUBMITTING to setOf(JobStatus.APPLIED, JobStatus.FAILED),
                JobStatus.APPLIED to setOf(JobStatus.REPLIED, JobStatus.REJECTED),
                JobStatus.REPLIED to setOf(JobStatus.INTERVIEW, JobStatus.REJECTED),
                JobStatus.INTERVIEW to setOf(JobStatus.OFFER, JobStatus.REJECTED),
            )

        for (from in JobStatus.entries) {
            assertEquals("edges from $from", expected[from].orEmpty(), edges(from))
        }
    }

    @Test
    fun finalStatusesHaveNoPipelineExit() {
        for (status in JobStatus.entries.filter { it.isFinal }) {
            assertEquals("$status is final", emptySet<JobStatus>(), edges(status))
        }
    }

    @Test
    fun aJobNeverStaysInTheSameStatusAndNeverMovesBackwardsThroughApproval() {
        for (from in JobStatus.entries) {
            assertFalse("$from -> $from", JobTransitions.isAllowed(from, from))
            assertFalse("$from -> FOUND", JobTransitions.isAllowed(from, JobStatus.FOUND))
        }
        assertFalse(JobTransitions.isAllowed(JobStatus.APPROVED, JobStatus.SCORED))
        assertFalse(JobTransitions.isAllowed(JobStatus.APPLIED, JobStatus.APPROVED))
    }

    @Test
    fun readyIsNotReachableBeforeTheUserApproves() {
        val into = JobStatus.entries.filter { JobTransitions.isAllowed(it, JobStatus.READY) }

        assertEquals(setOf(JobStatus.APPROVED, JobStatus.NEEDS_INPUT), into.toSet())
        assertFalse(JobTransitions.isAllowed(JobStatus.SCORED, JobStatus.READY))
        assertFalse(JobTransitions.isAllowed(JobStatus.FOUND, JobStatus.READY))
    }

    @Test
    fun theOnlyWayToAppliedIsThroughASubmittingAttempt() {
        val into = JobStatus.entries.filter { JobTransitions.isAllowed(it, JobStatus.APPLIED) }

        assertEquals(listOf(JobStatus.SUBMITTING), into)
    }

    @Test
    fun everyStatusCanBeReachedFromFound() {
        val seen = mutableSetOf(JobStatus.FOUND)
        val queue = ArrayDeque(listOf(JobStatus.FOUND))
        while (queue.isNotEmpty()) {
            for (next in edges(queue.removeFirst())) if (seen.add(next)) queue += next
        }

        assertEquals(JobStatus.entries.toSet(), seen)
    }

    @Test
    fun manualChangesAllowAnyShownStatusExceptTheCurrentOne() {
        for (from in JobStatus.entries) {
            for (to in JobStatus.entries) {
                val expected = from != to && !to.isInternal
                assertEquals("manual $from -> $to", expected, JobTransitions.isAllowedManually(from, to))
            }
        }
        assertTrue(JobTransitions.isAllowedManually(JobStatus.FOUND, JobStatus.OFFER))
        assertTrue(JobTransitions.isAllowedManually(JobStatus.FAILED, JobStatus.APPROVED))
        assertFalse(JobTransitions.isAllowedManually(JobStatus.APPROVED, JobStatus.SUBMITTING))
        assertFalse(JobTransitions.isAllowedManually(JobStatus.APPROVED, JobStatus.READY))
    }

    @Test
    fun applicationEdgesAreExactlyTheDocumentedOnes() {
        val expected =
            mapOf(
                ApplicationStatus.SUBMITTING to
                    setOf(ApplicationStatus.SUBMITTED, ApplicationStatus.CHECK_MANUALLY, ApplicationStatus.FAILED),
                ApplicationStatus.CHECK_MANUALLY to setOf(ApplicationStatus.SUBMITTED, ApplicationStatus.FAILED),
                ApplicationStatus.APPLY_MANUALLY to setOf(ApplicationStatus.SUBMITTED),
            )

        for (from in ApplicationStatus.entries) {
            assertEquals("edges from $from", expected[from].orEmpty(), ApplicationTransitions.next(from))
        }
    }

    @Test
    fun aSubmittingAttemptIsNeverRetriedAndSubmittedIsFinal() {
        for (to in ApplicationStatus.entries) {
            assertFalse("SUBMITTED -> $to", ApplicationTransitions.isAllowed(ApplicationStatus.SUBMITTED, to))
            assertFalse("FAILED -> $to", ApplicationTransitions.isAllowed(ApplicationStatus.FAILED, to))
            assertFalse("-> SUBMITTING from $to", ApplicationTransitions.isAllowed(to, ApplicationStatus.SUBMITTING))
        }
    }
}
