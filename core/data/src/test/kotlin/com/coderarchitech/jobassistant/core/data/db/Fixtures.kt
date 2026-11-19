package com.coderarchitech.jobassistant.core.data.db

import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.AiTask
import com.coderarchitech.jobassistant.core.model.Application
import com.coderarchitech.jobassistant.core.model.ApplicationStatus
import com.coderarchitech.jobassistant.core.model.ApplyMode
import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.core.model.CompanyWatchlistEntry
import com.coderarchitech.jobassistant.core.model.DailyReport
import com.coderarchitech.jobassistant.core.model.DealBreakers
import com.coderarchitech.jobassistant.core.model.Decision
import com.coderarchitech.jobassistant.core.model.Draft
import com.coderarchitech.jobassistant.core.model.DraftKind
import com.coderarchitech.jobassistant.core.model.Education
import com.coderarchitech.jobassistant.core.model.Job
import com.coderarchitech.jobassistant.core.model.JobFilters
import com.coderarchitech.jobassistant.core.model.JobStatus
import com.coderarchitech.jobassistant.core.model.LoginMethod
import com.coderarchitech.jobassistant.core.model.MatchResult
import com.coderarchitech.jobassistant.core.model.OutreachChannel
import com.coderarchitech.jobassistant.core.model.OutreachMessage
import com.coderarchitech.jobassistant.core.model.OutreachStatus
import com.coderarchitech.jobassistant.core.model.Portal
import com.coderarchitech.jobassistant.core.model.PortalType
import com.coderarchitech.jobassistant.core.model.Preferences
import com.coderarchitech.jobassistant.core.model.Profile
import com.coderarchitech.jobassistant.core.model.RemoteEligibility
import com.coderarchitech.jobassistant.core.model.Role
import com.coderarchitech.jobassistant.core.model.Run
import com.coderarchitech.jobassistant.core.model.RunError
import com.coderarchitech.jobassistant.core.model.RunSchedule
import com.coderarchitech.jobassistant.core.model.RunStage
import com.coderarchitech.jobassistant.core.model.RunStatus
import com.coderarchitech.jobassistant.core.model.Salary
import com.coderarchitech.jobassistant.core.model.ScoreStage
import com.coderarchitech.jobassistant.core.model.SearchPreset
import com.coderarchitech.jobassistant.core.model.Seniority
import com.coderarchitech.jobassistant.core.model.Skill
import com.coderarchitech.jobassistant.core.model.SourceCursor
import com.coderarchitech.jobassistant.core.model.StandardAnswer
import com.coderarchitech.jobassistant.core.model.Tier
import com.coderarchitech.jobassistant.core.model.UsageRecord
import com.coderarchitech.jobassistant.core.model.Verdict
import com.coderarchitech.jobassistant.core.model.WorkMode
import com.coderarchitech.jobassistant.core.model.WorkModePreference
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

// Sample values for DAO tests. Instants use whole milliseconds because that is what the database
// stores. Nothing here is a real credential or person.

/** A fixed point in time, in whole milliseconds. */
val T0: Instant = Instant.ofEpochMilli(1_800_000_000_000)

@Suppress("LongParameterList") // A test builder: every field a test varies has a default.
fun sampleJob(
    id: Long = 0,
    source: String = "greenhouse:acme",
    externalId: String = "123",
    status: JobStatus = JobStatus.FOUND,
    canonicalUrl: String = "https://boards.example.test/acme/jobs/123",
    dedupeHash: String = "hash-acme-engineer",
    salary: Salary? = Salary(min = 1_200_000, max = 1_800_000, currency = "INR"),
    firstSeenAt: Instant = T0,
    aliasOfJobId: Long? = null,
) = Job(
    id = id,
    source = source,
    externalId = externalId,
    company = "Acme",
    title = "Android Engineer",
    location = "Bengaluru",
    workMode = WorkMode.HYBRID,
    regionRestriction = null,
    salary = salary,
    description = "Build the Android app.",
    url = "https://boards.example.test/acme/jobs/123?utm_source=x",
    canonicalUrl = canonicalUrl,
    postedAt = T0.minusSeconds(3_600),
    dedupeHash = dedupeHash,
    status = status,
    firstSeenAt = firstSeenAt,
    aliasOfJobId = aliasOfJobId,
)

fun sampleMatch(
    jobId: Long,
    profileVersion: Int = 1,
    score: Int = 80,
) = MatchResult(
    jobId = jobId,
    profileVersion = profileVersion,
    score = score,
    remoteEligible = RemoteEligibility.UNCLEAR,
    matchedSkills = listOf("Kotlin", "Compose"),
    missingSkills = listOf("Rust"),
    redFlags = listOf("On-call"),
    reason = "Strong Kotlin match",
    decidedBy = ScoreStage.LLM,
    model = "test-model",
    scoredAt = T0,
)

fun sampleDraft(
    jobId: Long,
    kind: DraftKind = DraftKind.COVER_LETTER,
    content: String = "Dear team",
) = Draft(
    id = 0,
    jobId = jobId,
    kind = kind,
    content = content,
    unknownFields = listOf("Expected CTC"),
    editedByUser = false,
    createdAt = T0,
)

fun sampleDecision(
    jobId: Long,
    verdict: Verdict = Verdict.APPROVE,
    decidedAt: Instant = T0,
) = Decision(id = 0, jobId = jobId, verdict = verdict, reason = "Good fit", decidedAt = decidedAt)

fun samplePortal(
    id: Long = 0,
    name: String = "Naukri",
    enabled: Boolean = true,
) = Portal(
    id = id,
    name = name,
    type = PortalType.BUILT_IN,
    adapterId = "naukri",
    loginUrl = "https://portal.example.test/login",
    searchUrl = "https://portal.example.test/search",
    loginMethod = LoginMethod.OTP,
    enabled = enabled,
)

fun sampleApplication(
    jobId: Long,
    portalId: Long? = null,
    status: ApplicationStatus = ApplicationStatus.SUBMITTING,
) = Application(
    id = 0,
    jobId = jobId,
    portalId = portalId,
    status = status,
    mode = ApplyMode.REVIEWED,
    attemptId = "attempt-1",
    submittingAt = T0,
    submittedAt = null,
    proofPath = null,
    answersUsed = mapOf("Notice period" to "30 days"),
)

fun sampleOutreach(
    jobId: Long,
    status: OutreachStatus = OutreachStatus.SENT,
    followUpDueAt: Instant? = T0.plusSeconds(7 * 24 * 3_600),
    repliedAt: Instant? = null,
) = OutreachMessage(
    id = 0,
    jobId = jobId,
    channel = OutreachChannel.EMAIL,
    recipient = "recruiter@example.test",
    draft = "Hello",
    status = status,
    gmailThreadId = "thread-1",
    sentAt = T0,
    repliedAt = repliedAt,
    followUpDueAt = followUpDueAt,
)

fun sampleProfile(version: Int = 1) =
    Profile(
        resumeText = "Sample resume text",
        skills = listOf(Skill("Kotlin", 4.5), Skill("Compose")),
        experience =
            listOf(
                Role("Acme", "Engineer", YearMonth.of(2020, 1), YearMonth.of(2023, 6), "Built things"),
                Role("Beta", "Senior Engineer", YearMonth.of(2023, 7), null),
            ),
        education = listOf(Education("Example University", "B.Tech", 2019)),
        preferences =
            Preferences(
                targetRoles = listOf("Android Engineer"),
                seniority = Seniority.SENIOR,
                minSalary = Salary(2_000_000, null, "INR"),
                noticePeriodDays = 30,
                workMode = WorkModePreference.REMOTE_OR_HYBRID,
                locations = listOf("Bengaluru"),
                timeZones = listOf("Asia/Kolkata"),
            ),
        dealBreakers = DealBreakers(industries = listOf("Crypto"), keywords = listOf("on-site only")),
        answersBank = listOf(StandardAnswer("Notice period?", "30 days")),
        writingSamples = listOf("Hi, I am writing about..."),
        version = version,
        updatedAt = T0,
    )

fun samplePreset(
    id: Long = 0,
    name: String = "Android remote",
) = SearchPreset(
    id = id,
    name = name,
    keywords = listOf("android", "kotlin"),
    portalIds = listOf(1L, 2L),
    context = "No service companies",
    filters = JobFilters(tiers = setOf(Tier.HIGH), workModes = setOf(WorkMode.REMOTE), postedWithinDays = 7),
    schedule = RunSchedule(),
)

fun sampleWatchlistEntry(
    atsType: AtsType = AtsType.GREENHOUSE,
    boardSlug: String = "acme",
) = CompanyWatchlistEntry(id = 0, name = "Acme", atsType = atsType, boardSlug = boardSlug)

fun sampleCursor(sourceKey: String = "remotive") = SourceCursor(sourceKey, T0, "ext-99")

fun sampleRun(
    presetId: Long? = null,
    startedAt: Instant = T0,
) = Run(
    id = 0,
    presetId = presetId,
    startedAt = startedAt,
    endedAt = null,
    status = RunStatus.RUNNING,
    countsPerStage = mapOf(RunStage.DISCOVER to 12, RunStage.SCORE to 7),
    errors = listOf(RunError(RunStage.DISCOVER, "HTTP 500", "remotive")),
)

fun sampleUsage(
    runId: Long?,
    costMicros: Long,
    recordedAt: Instant = T0,
) = UsageRecord(
    id = 0,
    runId = runId,
    provider = AiProvider.ANTHROPIC,
    model = "test-model",
    task = AiTask.SCORE,
    inputTokens = 1_500,
    outputTokens = 200,
    cachedTokens = 1_000,
    estimatedCostMicros = costMicros,
    recordedAt = recordedAt,
)

fun sampleReport(date: LocalDate = LocalDate.of(2026, 11, 19)) =
    DailyReport(
        date = date,
        countsByStatus = mapOf(JobStatus.FOUND to 20, JobStatus.APPLIED to 4),
        appliedByPortal = mapOf(1L to 3, 2L to 1),
        emailsSent = 2,
        linkedInDraftsPending = 1,
        replies = 1,
        failures = 0,
        costMicros = 450_000,
    )
